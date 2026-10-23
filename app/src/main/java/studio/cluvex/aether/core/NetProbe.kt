package studio.cluvex.aether.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/** Public IP + country as reported by a geolocation endpoint. */
data class IpInfo(val ip: String, val countryCode: String?)

/** IP shown in the UI, tagged with whether it came through the tunnel. */
data class IpEndpoint(val ip: String, val countryCode: String?, val viaTunnel: Boolean)

/**
 * Low-level, dependency-free network probes.
 *
 * These use RAW sockets on purpose:
 *   1. Raw sockets are exempt from Android's cleartext-traffic policy, so the
 *      plain-HTTP geolocation probe works without extra manifest config.
 *   2. A hand-rolled SOCKS5 client lets us send the destination as a DOMAIN
 *      (ATYP=0x03) so DNS is resolved REMOTELY by the engine — exactly what a
 *      real browser tab does through the tunnel. That makes the probe a true
 *      end-to-end test of DNS + TCP + the proxy path in one shot.
 *
 * Because the app package is excluded from the VPN (addDisallowedApplication),
 * a direct socket bypasses the tunnel (→ operator/real IP) while a socket routed
 * through 127.0.0.1:1819 exits via the connected server (→ server IP).
 */
object NetProbe {

    /**
     * Geolocation endpoints, tried IN ORDER until one answers.
     *
     * ROOT-CAUSE NOTE: relying on a single provider (ip-api.com over plain
     * HTTP:80) was a single point of failure — on many operator networks that
     * host/port simply never answers, so the IP badge silently showed nothing
     * even while fully disconnected. Cloudflare's /cdn-cgi/trace is served
     * from an anycast edge (reachable on nearly every network) and is used as
     * the fallback — once via TLS with SNI, and once via the bare IP literal
     * 1.1.1.1 which needs no DNS at all.
     */
    private data class GeoProvider(
        val host: String,
        val port: Int,
        val path: String,
        val tls: Boolean,
        /** false = host is an IPv4 literal (SOCKS ATYP=0x01, no DNS involved). */
        val hostIsDomain: Boolean,
    )

    /**
     * SECURITY (audit 1.2.7-r2, tightened in 1.3.0): TLS ONLY, on purpose.
     *
     * The order used to be cleartext-first (`ip-api.com:80`), so on a healthy
     * network the exit IP shown in the UI always arrived over plain HTTP - which
     * anyone on the path can rewrite. On the networks this app exists for that is
     * not theoretical: a censor able to answer the probe can make the badge show a
     * plausible foreign IP and flag while the traffic is not tunnelled at all,
     * turning the app's own reassurance into the attack. 1.2.7-r2 moved the
     * authenticated provider first and kept two cleartext entries as an
     * availability fallback.
     *
     * AUDIT F-5 (1.3.0): those two are gone. `1.1.1.1` serves the same
     * `/cdn-cgi/trace` on 443, so the fallback costs nothing to authenticate, and
     * `ip-api.com` as an IP source is dropped entirely. Two reasons beyond the
     * rewrite risk: a cleartext GET for "what is my IP" is a recognisable
     * fingerprint of this app on a network where the app's presence is itself the
     * sensitive fact, and these are raw sockets, so `usesCleartextTraffic=false`
     * never applied to them.
     *
     * The IP literal is deliberate ([GeoProvider.hostIsDomain] = false): no DNS is
     * involved, so a poisoned resolver cannot redirect the probe. Verification
     * still holds - the Cloudflare DNS certificate carries `1.1.1.1` as an
     * iPAddress SAN and [tlsWrap] checks it through the platform verifier.
     *
     * The country REFINEMENT (see [refineCountry]) still uses ip-api over plain
     * HTTP, because its free tier offers no TLS - but F-5 left that call reachable
     * from the DIRECT path, which was the actual leak and is fixed in
     * [shouldRefineCountry]. The label is informational and must never be treated
     * as proof of anything; the exit IP, which is what the self-test and the user
     * rely on, now only ever comes from an authenticated source.
     */
    private val GEO_PROVIDERS = listOf(
        GeoProvider("www.cloudflare.com", 443, "/cdn-cgi/trace", tls = true, hostIsDomain = true),
        GeoProvider("1.1.1.1", 443, "/cdn-cgi/trace", tls = true, hostIsDomain = false),
    )

    // ---- Public: geolocation --------------------------------------------

    /** Real/operator IP (direct, bypasses the tunnel). */
    fun fetchIpInfoDirect(timeoutMs: Int = 8000): IpInfo? {
        for (p in GEO_PROVIDERS) {
            val info = runCatching {
                openDirectIpv4(p.host, p.port, timeoutMs).use { s ->
                    val io = if (p.tls) tlsWrap(s, p.host, p.port, timeoutMs) else s
                    parseIpInfo(httpGet(io, p.host, p.path))
                }
            }.onFailure {
                DiagnosticsLog.d("netprobe", "direct geo ${p.host} failed: ${it.message}")
            }.getOrNull()
            // SECURITY (F-5 follow-up): NO country refinement on this path.
            //
            // This is the UNTUNNELLED probe - it exists to show the operator's own
            // IP while disconnected. Refining the country here opened a SECOND,
            // CLEARTEXT connection (ip-api.com:80, a raw socket, so
            // `usesCleartextTraffic="false"` never applied) whose request line
            // carried this device's real public IP, sent over the very network the
            // app is supposed to protect the user from. The operator already knows
            // that IP - it is the source address of the packet - so the leak is not
            // the address itself: it is a plaintext, app-shaped
            // "GET /json/<my own ip>?fields=status,countryCode" that identifies
            // Aether on a network where merely running Aether is the sensitive
            // fact. The flag on a disconnected badge is not worth that.
            //
            // The provider's own `loc=` is used as-is; no refinement, no second
            // request. See [shouldRefineCountry] for the rule and the test.
            if (info != null) return info
        }
        return null
    }

    /**
     * Opens a DIRECT (non-proxied) socket, forcing IPv4.
     *
     * ROOT-CAUSE FIX (MCI / Hamrah-e-Aval cellular): on dual-stack mobile data
     * the default dual-stack connect prefers IPv6, so the "your IP" badge
     * surfaced a scoped/temporary IPv6 address instead of the operator's real
     * public IPv4 (on Wi-Fi, often IPv4-only, it already looked correct). We
     * resolve the host and connect to its IPv4 address explicitly, so the geo
     * endpoint always sees — and reports — the real IPv4.
     */
    private fun openDirectIpv4(host: String, port: Int, timeoutMs: Int): Socket {
        val addr = resolveIpv4(host)
        return Socket().apply {
            connect(InetSocketAddress(addr, port), timeoutMs)
            soTimeout = timeoutMs
        }
    }

    /** Resolves [host] to an IPv4 address (falls back to the first address). */
    private fun resolveIpv4(host: String): InetAddress {
        val all = InetAddress.getAllByName(host)
        return all.firstOrNull { it is Inet4Address } ?: all.first()
    }

    /** Exit/server IP (routed through the local SOCKS5 proxy). */
    fun fetchIpInfoViaSocks(
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int = 9000,
    ): IpInfo? {
        for (p in GEO_PROVIDERS) {
            val info = runCatching {
                socks5Connect(socksHost, socksPort, p.host, p.port, useDomain = p.hostIsDomain, timeoutMs).use { s ->
                    val io = if (p.tls) tlsWrap(s, p.host, p.port, timeoutMs) else s
                    parseIpInfo(httpGet(io, p.host, p.path))
                }
            }.onFailure {
                DiagnosticsLog.d("netprobe", "proxied geo ${p.host} failed: ${it.message}")
            }.getOrNull()
            if (info != null) {
                return refineCountry(info, p, viaTunnel = true) { host, port ->
                    socks5Connect(socksHost, socksPort, host, port, useDomain = true, timeoutMs)
                }
            }
        }
        return null
    }


    /**
     * SPEED FIX: races ALL geolocation providers through the proxy in
     * PARALLEL and returns the first success. The serial fallback chain in
     * [fetchIpInfoViaSocks] burns up to one full timeout per filtered
     * provider — and which provider is filtered/slow varies per operator and
     * region (DPI variance), so some users waited tens of seconds before the
     * provider that actually works on their network was even tried. The race
     * always completes as fast as the FASTEST provider for the current user.
     */
    fun fetchIpInfoViaSocksRaced(
        socksHost: String,
        socksPort: Int,
        timeoutMs: Int = 6000,
    ): IpInfo? {
        val winner = AtomicReference<IpInfo?>(null)
        val remaining = AtomicInteger(GEO_PROVIDERS.size)
        val done = CountDownLatch(1)
        for (p in GEO_PROVIDERS) {
            // 1.2.2 CPU/MEMORY FIX: run the race on a SHARED, recycled pool
            // instead of spawning brand-new OS threads on every call. This
            // function runs on connect, on every reconnect and on each IP
            // refresh; each invocation used to create one raw thread per
            // provider (each with its own stack) that was then thrown away.
            // A cached pool reuses those workers, so repeated refreshes stop
            // churning thread stacks and the GC.
            geoPool.execute {
                val info = runCatching {
                    socks5Connect(socksHost, socksPort, p.host, p.port, useDomain = p.hostIsDomain, timeoutMs).use { s ->
                        val io = if (p.tls) tlsWrap(s, p.host, p.port, timeoutMs) else s
                        parseIpInfo(httpGet(io, p.host, p.path))
                    }
                }.onFailure {
                    DiagnosticsLog.d("netprobe", "raced geo ${p.host} failed: ${it.message}")
                }.getOrNull()
                if (info != null) {
                    val refined = runCatching {
                        refineCountry(info, p, viaTunnel = true) { host, port ->
                            socks5Connect(socksHost, socksPort, host, port, useDomain = true, timeoutMs)
                        }
                    }.getOrDefault(info)
                    if (winner.compareAndSet(null, refined)) done.countDown()
                }
                if (remaining.decrementAndGet() == 0) done.countDown()
            }
        }
        // Small headroom over the per-connection timeout for TLS + HTTP I/O.
        done.await(timeoutMs.toLong() + 4_000L, TimeUnit.MILLISECONDS)
        return winner.get()
    }

    /**
     * Shared worker pool for the geolocation race (see
     * [fetchIpInfoViaSocksRaced]). Threads are daemons and idle ones are
     * reclaimed after 30 s, so the pool costs nothing while disconnected.
     */
    private val geoPool = Executors.newCachedThreadPool(
        object : ThreadFactory {
            private val counter = AtomicInteger(0)
            override fun newThread(r: Runnable): Thread =
                Thread(r, "geo-race-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
        },
    )

    /** Wraps an already-connected socket in TLS (SNI = [host]). */
    private fun tlsWrap(socket: Socket, host: String, port: Int, timeoutMs: Int): Socket {
        // getDefault() is statically typed as the plain SocketFactory, which
        // lacks the (socket, host, port, autoClose) overload — cast first.
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        // SECURITY FIX (MitM): SSLSocket.startHandshake() validates the chain
        // against the system trust store but does NOT check that the
        // certificate actually belongs to [host]. Without this check an
        // attacker holding a valid certificate for ANY domain could intercept
        // the TLS geolocation probe. Enforce standard hostname verification.
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        if (!verifier.verify(host, ssl.session)) {
            runCatching { ssl.close() }
            throw SSLPeerUnverifiedException("Certificate does not match host $host (possible MitM)")
        }
        return ssl
    }

    /**
     * Whether a country refinement lookup may be made at all.
     *
     * SECURITY RULE (F-5 follow-up). The refinement is one plaintext HTTP request
     * to `ip-api.com:80` whose path contains an IP address. Two conditions have to
     * hold before that request is allowed to exist, and BOTH are enforced here so
     * there is exactly one place to audit and one place to test:
     *
     *  1. **Tunnelled only.** [viaTunnel] must be true. On a direct socket the
     *     request travels the operator's network in the clear, carrying the user's
     *     real IP in its request line and the recognisable shape of this app. That
     *     was the leak: it fired on every disconnected IP refresh, which is exactly
     *     when the user has no protection. Through the tunnel the request leaves
     *     from the exit, so neither the operator nor anyone between sees it, and
     *     the address in it is the exit address - not the user's.
     *  2. **Only when the country is actually unknown.** The refinement used to run
     *     on EVERY successful probe, purely to make two geo databases agree on a
     *     flag. A cosmetic request is still a request; it does not get to exist. If
     *     the provider already reported a country, that country is used.
     *
     * The [providerHost] check keeps the old guard: ip-api never re-asks itself.
     *
     * `internal` rather than `private` so `NetProbeGeoPolicyTest` can pin the rule
     * on the JVM. It is a pure function - no socket, no Android type.
     */
    internal fun shouldRefineCountry(
        providerHost: String,
        countryCode: String?,
        viaTunnel: Boolean,
    ): Boolean {
        if (!viaTunnel) return false
        if (providerHost == "ip-api.com") return false
        return countryCode.isNullOrBlank()
    }

    /**
     * Fills in a MISSING country code, through the tunnel, or returns [info]
     * unchanged.
     *
     * Background: Cloudflare's /cdn-cgi/trace `loc=` is the country Cloudflare
     * attributes to the CLIENT, which behind WARP often differs from the country
     * the exit IP is registered in (what ip-api reports), so the badge could show a
     * different flag for the same connection depending on which provider won. That
     * cosmetic disagreement is NO LONGER worth a network request: harmonising it
     * cost a cleartext lookup on every probe, and on the direct path that lookup
     * carried the user's real IP over the operator's network. Now the lookup only
     * happens when there is no country at all to show, and only inside the tunnel -
     * see [shouldRefineCountry], which is where the rule lives.
     *
     * [open] MUST return a tunnelled socket. Callers state this with [viaTunnel];
     * a caller that cannot honour it must not call this function.
     */
    private fun refineCountry(
        info: IpInfo,
        provider: GeoProvider,
        viaTunnel: Boolean,
        open: (host: String, port: Int) -> Socket,
    ): IpInfo {
        if (!shouldRefineCountry(provider.host, info.countryCode, viaTunnel)) return info
        val cc = runCatching {
            open("ip-api.com", 80).use { s ->
                val body = httpGet(s, "ip-api.com", "/json/${info.ip}?fields=status,countryCode")
                Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            }
        }.getOrNull()
        return if (cc != null) info.copy(countryCode = cc) else info
    }

    // ---- Retry wrappers -------------------------------------------------
    //
    // The IP badge used to fire a SINGLE lookup keyed on the connection phase.
    // Right after connect, the warp-in-warp tunnel is still cold, so the first
    // request loses the race and the badge is stuck on "unavailable" forever
    // (there was no retry). These helpers ride out that cold-start window. They
    // block between attempts, so call them off the main thread (Dispatchers.IO).

    /** [fetchIpInfoDirect] with retries for a flaky operator network. */
    fun fetchIpInfoDirectWithRetry(
        attempts: Int = 6,
        delayMs: Long = 2000,
        timeoutMs: Int = 8000,
    ): IpInfo? {
        repeat(attempts) { i ->
            fetchIpInfoDirect(timeoutMs)?.let { return it }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    /** [fetchIpInfoViaSocks] with retries to cover the tunnel's cold start. */
    fun fetchIpInfoViaSocksWithRetry(
        socksHost: String,
        socksPort: Int,
        // SPEED FIX: raced providers + 1 s pacing instead of serial providers
        // + 3 s pacing — same total patience, far lower time-to-first-answer.
        attempts: Int = 12,
        delayMs: Long = 1000,
        timeoutMs: Int = 6000,
    ): IpInfo? {
        repeat(attempts) { i ->
            fetchIpInfoViaSocksRaced(socksHost, socksPort, timeoutMs)?.let { return it }
            if (i < attempts - 1) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    // ---- Public: connectivity self-tests --------------------------------

    /** SOCKS5 greeting only — proves the engine speaks SOCKS5. */
    fun checkSocksHandshake(socksHost: String, socksPort: Int, timeoutMs: Int = 4000): Boolean =
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(byteArrayOf(0x05, 0x01, 0x00)); flush() }
                val reply = ByteArray(2)
                DataInputStream(s.getInputStream()).readFully(reply)
                reply[0].toInt() == 0x05 && reply[1].toInt() == 0x00
            }
        }.getOrDefault(false)

    /** Full SOCKS5 CONNECT to an IP literal — proves TCP proxying works (no DNS). */
    fun checkTcpViaProxy(
        socksHost: String,
        socksPort: Int,
        destIp: String,
        destPort: Int,
        timeoutMs: Int = 6000,
    ): Boolean = runCatching {
        socks5Connect(socksHost, socksPort, destIp, destPort, useDomain = false, timeoutMs).use { true }
    }.getOrDefault(false)

    /**
     * Resolves a name over SOCKS5 **UDP ASSOCIATE** — the exact path every app on
     * the device uses for DNS once the TUN is up.
     *
     * ## Why this check exists
     *
     * `Aether -> Psiphon` shipped connected and unable to open a single site, and
     * the four-step self-test passed the whole time. It passed because
     * [fetchIpInfoViaSocksRaced] resolves through a SOCKS5 `CONNECT` carrying a
     * HOSTNAME, which the proxy resolves remotely on the TCP path. hev-socks5-tunnel
     * does not do that: it carries UDP over `UDP ASSOCIATE`, and Psiphon's own
     * SOCKS listener answers that command with a refusal
     * (`socks5ReadCommand: SOCKS message field command was 0x03, not 0x01`). So the
     * one code path the device actually depends on was the one path nothing tested.
     *
     * This probe speaks the same protocol hev does, against the same port, so the
     * gap cannot reopen: if the device would not be able to resolve a name, this
     * fails and the session is never reported Connected.
     *
     * @return true when a well-formed DNS response comes back with our own query
     *   id. `NXDOMAIN` counts as success — the point is that a real resolver
     *   answered through the tunnel, not what it said.
     */
    fun checkDnsViaSocksUdp(
        socksHost: String,
        socksPort: Int,
        resolver: String = "1.1.1.1",
        name: String = "cloudflare.com",
        timeoutMs: Int = 8000,
    ): Boolean = runCatching {
        Socket().use { control ->
            control.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            control.soTimeout = timeoutMs
            val out = control.getOutputStream()
            val input = DataInputStream(control.getInputStream())

            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            val greeting = ByteArray(2)
            input.readFully(greeting)
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) return@runCatching false

            // UDP ASSOCIATE with a wildcard DST, per RFC 1928 §4: the client does
            // not yet know which source address it will send from, and every
            // forwarder this app uses asks exactly this way.
            out.write(
                byteArrayOf(
                    0x05, 0x03, 0x00, 0x01,
                    0, 0, 0, 0,
                    0, 0,
                )
            )
            out.flush()

            if (input.read() != 0x05) return@runCatching false
            val reply = input.read()
            input.read() // reserved
            val boundHost = when (input.read()) {
                0x01 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) })
                0x04 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) })
                0x03 -> {
                    val len = input.read()
                    InetAddress.getByName(String(ByteArray(len).also { input.readFully(it) }))
                }
                else -> return@runCatching false
            }
            val boundPort = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)
            // rep=7 (command not supported) is the precise failure this probe was
            // written for. Report it plainly instead of as a generic timeout.
            if (reply != 0x00) return@runCatching false
            if (boundPort == 0) return@runCatching false

            // A BND.ADDR of 0.0.0.0 means "the address you already reached me on".
            val target =
                if (boundHost.address.all { it.toInt() == 0 }) InetAddress.getByName(socksHost)
                else boundHost

            val query = buildDnsQuery(name)
            val request = ByteArray(10 + query.size).apply {
                this[0] = 0; this[1] = 0; this[2] = 0 // RSV RSV FRAG
                this[3] = 0x01                        // ATYP IPv4
                System.arraycopy(InetAddress.getByName(resolver).address, 0, this, 4, 4)
                this[8] = 0                           // port 53, big-endian
                this[9] = 53
                System.arraycopy(query, 0, this, 10, query.size)
            }

            DatagramSocket().use { udp ->
                udp.soTimeout = timeoutMs
                udp.send(DatagramPacket(request, request.size, target, boundPort))
                val buffer = ByteArray(2048)
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                isDnsAnswerFor(query, buffer, packet.length)
            }
        }
    }.getOrDefault(false)

    /**
     * Validates a SOCKS5 UDP reply as a DNS answer to [query].
     *
     * Split out of [checkDnsViaSocksUdp] so the header walk has somewhere to bail
     * out from: a `return@use` inside two nested `use` blocks binds to the
     * innermost label, which is the kind of thing that reads correctly and
     * behaves otherwise.
     */
    private fun isDnsAnswerFor(query: ByteArray, buffer: ByteArray, length: Int): Boolean {
        if (length < 10) return false
        // Strip the SOCKS5 UDP reply header, which mirrors the request's address
        // form: RSV(2) FRAG(1) ATYP(1) ADDR PORT(2).
        val addressLength = when (buffer[3].toInt() and 0xFF) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> (buffer[4].toInt() and 0xFF) + 1
            else -> return false
        }
        val offset = 4 + addressLength + 2
        if (length < offset + 12) return false
        val id = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val expectedId = ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)
        val isResponse = (buffer[offset + 2].toInt() and 0x80) != 0
        return id == expectedId && isResponse
    }

    /** Minimal `A` query for [name]: 12-byte header, one question, no EDNS. */
    private fun buildDnsQuery(name: String): ByteArray {
        val body = ByteArrayOutputStream()
        val id = (1..0xFFFE).random()
        body.write((id shr 8) and 0xFF)
        body.write(id and 0xFF)
        body.write(0x01); body.write(0x00) // standard query, recursion desired
        body.write(0x00); body.write(0x01) // QDCOUNT = 1
        body.write(0x00); body.write(0x00) // ANCOUNT
        body.write(0x00); body.write(0x00) // NSCOUNT
        body.write(0x00); body.write(0x00) // ARCOUNT
        name.split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            body.write(bytes.size)
            body.write(bytes)
        }
        body.write(0x00)                   // root label
        body.write(0x00); body.write(0x01) // QTYPE  A
        body.write(0x00); body.write(0x01) // QCLASS IN
        return body.toByteArray()
    }

    // ---- SOCKS5 core ----------------------------------------------------

    /**
     * Opens a TCP connection to [destHost]:[destPort] THROUGH the SOCKS5 proxy
     * and returns the live socket (caller closes). Throws on any failure.
     */
    private fun socks5Connect(
        socksHost: String,
        socksPort: Int,
        destHost: String,
        destPort: Int,
        useDomain: Boolean,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            // Greeting: VER=5, 1 method, NO-AUTH.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            input.readFully(greeting)
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                throw IOException("SOCKS5 auth negotiation failed")
            }

            // Request: CONNECT.
            val req = ByteArrayOutputStream()
            req.write(0x05)
            req.write(0x01)
            req.write(0x00)
            if (useDomain) {
                val host = destHost.toByteArray(Charsets.US_ASCII)
                req.write(0x03)
                req.write(host.size)
                req.write(host)
            } else {
                val octets = destHost.split(".")
                if (octets.size != 4) throw IOException("bad IPv4 literal: $destHost")
                req.write(0x01)
                octets.forEach { req.write(it.toInt() and 0xFF) }
            }
            req.write((destPort ushr 8) and 0xFF)
            req.write(destPort and 0xFF)
            out.write(req.toByteArray())
            out.flush()

            // Reply: VER, REP, RSV, ATYP, BND.ADDR, BND.PORT.
            val head = ByteArray(4)
            input.readFully(head)
            if (head[1].toInt() != 0x00) {
                throw IOException("SOCKS5 CONNECT rejected (rep=${head[1].toInt() and 0xFF})")
            }
            val skip = when (head[3].toInt() and 0xFF) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> {
                    val len = ByteArray(1)
                    input.readFully(len)
                    len[0].toInt() and 0xFF
                }
                else -> throw IOException("SOCKS5 bad ATYP in reply")
            }
            input.readFully(ByteArray(skip))
            input.readFully(ByteArray(2)) // BND.PORT
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    // ---- Minimal HTTP/1.1 -----------------------------------------------

    private fun httpGet(socket: Socket, host: String, path: String): String {
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            // SECURITY (F-5 follow-up): NO app name on the wire. This header used
            // to read "Aether/1.0", which named the app - and its version - in
            // every probe, including the one plaintext request this class can still
            // make. On a network where running this app is itself the sensitive
            // fact, that is the fingerprint, more direct than the shape of the
            // request. A bare, extremely common token identifies nothing.
            append("User-Agent: Mozilla/5.0\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun parseIpInfo(response: String): IpInfo? {
        val body = response.substringAfter("\r\n\r\n", "")
        // Format 1: ip-api.com JSON  {"query":"1.2.2.4","countryCode":"DE"}
        val ip = Regex("\"query\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        if (ip != null) {
            val cc = Regex("\"countryCode\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return IpInfo(ip, cc)
        }
        // Format 2: Cloudflare /cdn-cgi/trace key=value lines (ip=..., loc=...)
        val traceIp = Regex("(?m)^ip=(\\S+)").find(body)?.groupValues?.get(1)
        if (traceIp != null) {
            val loc = Regex("(?m)^loc=([A-Za-z]{2})").find(body)?.groupValues?.get(1)
            return IpInfo(traceIp, loc?.uppercase())
        }
        return null
    }

    /** Turns a 2-letter ISO country code into its flag emoji. */
    fun flagEmoji(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return "\uD83C\uDFF3\uFE0F"
        val cc = countryCode.uppercase()
        if (!cc.all { it in 'A'..'Z' }) return "\uD83C\uDFF3\uFE0F"
        val base = 0x1F1E6
        val first = base + (cc[0].code - 'A'.code)
        val second = base + (cc[1].code - 'A'.code)
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }
}
