package studio.cluvex.aether.custom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Real delay of a custom config, measured THROUGH the config.
 *
 * ## Why the old probe reported -1 / "Filtered" for configs that work
 *
 * The 1.5.0 probe opened its own TCP socket to the server and, for TLS and
 * REALITY configs, ran the platform's TLS handshake against it. That measures
 * something the session never does:
 *
 *  * A REALITY server only completes a handshake for a client that carries the
 *    config's REALITY key. A plain ClientHello is relayed to the camouflage site
 *    or held open, so the probe hit its timeout and reported "Filtered" for a
 *    perfectly healthy server.
 *  * The platform ClientHello (Conscrypt) is not the config's uTLS fingerprint,
 *    and filtering middleboxes treat the two differently.
 *  * The server name was resolved with the network's resolver, while the session
 *    resolves it with Xray's own DNS; a poisoned answer made the probe dial an
 *    address the session never uses.
 *  * A TCP connect proves little: transparent middleboxes accept every SYN and
 *    then drop the stream.
 *
 * ## What is measured now
 *
 * Exactly what v2rayNG's "real delay" measures: the config's own outbound, built
 * by the same code as a direct session ([XrayConfigBuilder.directOutbound]), runs
 * in the bundled Xray core, and one HTTP request for `generate_204` is sent
 * through it. The number is the time from the request to the first response line:
 * the config's handshake to its server plus one round trip to the probe target.
 * Two rounds are taken and the faster one reported, so a cold DNS lookup inside
 * the core does not decide the number.
 *
 * Many configs share one Xray process (one SOCKS inbound per config, routed to
 * its own outbound). A config the core refuses would stop the whole batch from
 * starting, so a batch that does not start is retried one config per process.
 *
 * Nothing here has Android types, so the whole path runs in JVM tests against a
 * real Xray binary.
 */
object RealDelay {

    /** Plain HTTP on purpose: one round trip through the config, no second TLS layer on top. */
    internal val TARGETS = listOf("www.gstatic.com", "cp.cloudflare.com")
    internal const val TARGET_PATH = "/generate_204"

    /** Budget for one request through the config, handshake included. */
    const val REQUEST_TIMEOUT_MS = 8_000

    /** How long a probe core may take to open its first listener. */
    const val START_TIMEOUT_MS = 6_000L

    /** Configs per Xray process. */
    const val BATCH_SIZE = 32

    /** Requests in flight at once, across all configs. */
    const val PARALLEL = 6

    /** A running probe core. */
    interface Core {
        fun isAlive(): Boolean
        fun stop()
    }

    /** Starts Xray with a config; [XrayProcess] on the device, a plain process in tests. */
    fun interface Launcher {
        fun start(configJson: String, tag: String): Core
    }

    /**
     * Credentials of the probe inbounds, fresh for every app start.
     *
     * The inbounds listen on loopback only, but any app on the phone can connect
     * to loopback; without a password a local app could use a probe inbound as a
     * free proxy, or to learn the config's exit, for as long as a test runs.
     */
    private val probeUser: String = randomToken()
    private val probePass: String = randomToken()

    private fun randomToken(): String {
        val bytes = ByteArray(12)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** One config to measure. */
    data class Item(val id: String, val outbound: String)

    sealed class Result {
        /** The config carried a request; [ms] is the faster of the rounds. */
        data class Ok(val ms: Long) : Result()

        /** The core refused this config (it cannot run at all). */
        data class Invalid(val reason: String) : Result()

        /** The core ran the config, and nothing came back through it. */
        data class NoReply(val reason: String) : Result()
    }

    /** The probe config: one loopback SOCKS inbound per entry, each routed to that entry's outbound. */
    fun buildConfig(entries: List<Pair<Item, Int>>, networkDns: List<String>): String = buildJsonObject {
        put("log", buildJsonObject {
            put("loglevel", "warning")
            put("access", "none")
        })
        put("dns", buildJsonObject {
            put("tag", XrayConfigBuilder.DNS_TAG)
            put("queryStrategy", "UseIP")
            put("servers", buildJsonArray {
                XrayConfigBuilder.directDns(networkDns).forEach { add(JsonPrimitive(it)) }
            })
        })
        put("inbounds", buildJsonArray {
            entries.forEachIndexed { i, (_, port) ->
                add(buildJsonObject {
                    put("tag", "$INBOUND_PREFIX$i")
                    put("listen", LOOPBACK)
                    put("port", port)
                    put("protocol", "socks")
                    put("settings", buildJsonObject {
                        put("auth", "password")
                        put("accounts", buildJsonArray {
                            add(buildJsonObject {
                                put("user", probeUser)
                                put("pass", probePass)
                            })
                        })
                        put("udp", false)
                    })
                    put("sniffing", buildJsonObject { put("enabled", false) })
                })
            }
        })
        put("outbounds", buildJsonArray {
            entries.forEachIndexed { i, (item, _) ->
                add(XrayConfigBuilder.directOutbound(item.outbound, "$OUTBOUND_PREFIX$i"))
            }
            add(buildJsonObject {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", buildJsonObject { put("domainStrategy", "UseIP") })
            })
        })
        put("policy", buildJsonObject {
            put("levels", buildJsonObject {
                put("0", buildJsonObject {
                    put("handshake", REQUEST_TIMEOUT_MS / 1000)
                    put("connIdle", 30)
                    put("uplinkOnly", 2)
                    put("downlinkOnly", 5)
                })
            })
        })
        put("routing", buildJsonObject {
            put("domainStrategy", "AsIs")
            put("rules", buildJsonArray {
                add(buildJsonObject {
                    put("type", "field")
                    put("inboundTag", buildJsonArray { add(JsonPrimitive(XrayConfigBuilder.DNS_TAG)) })
                    put("outboundTag", "direct")
                })
                entries.indices.forEach { i ->
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add(JsonPrimitive("$INBOUND_PREFIX$i")) })
                        put("outboundTag", "$OUTBOUND_PREFIX$i")
                    })
                }
            })
        })
    }.toString()

    /**
     * Measures every item and calls [onResult] as each finishes.
     *
     * [freePorts] hands out loopback ports for the probe inbounds.
     */
    suspend fun measureAll(
        items: List<Item>,
        launcher: Launcher,
        networkDns: List<String>,
        onResult: (String, Result) -> Unit,
        freePorts: (Int) -> List<Int> = ::freeLoopbackPorts,
    ) {
        val valid = items.filter { item ->
            val ok = runCatching { XrayConfigBuilder.directOutbound(item.outbound, "check") }.isSuccess
            if (!ok) onResult(item.id, Result.Invalid("stored outbound is not valid"))
            ok
        }
        val gate = Semaphore(PARALLEL)
        for ((index, batch) in valid.chunked(BATCH_SIZE).withIndex()) {
            runBatch(batch, "ping-$index", launcher, networkDns, gate, onResult, freePorts, splitOnFailure = true)
        }
    }

    private suspend fun runBatch(
        batch: List<Item>,
        tag: String,
        launcher: Launcher,
        networkDns: List<String>,
        gate: Semaphore,
        onResult: (String, Result) -> Unit,
        freePorts: (Int) -> List<Int>,
        splitOnFailure: Boolean,
    ) {
        val ports = freePorts(batch.size)
        val entries = batch.zip(ports)
        val core = withContext(Dispatchers.IO) {
            runCatching { launcher.start(buildConfig(entries, networkDns), tag) }.getOrNull()
        }
        if (core == null) {
            batch.forEach { onResult(it.id, Result.Invalid("the Xray core could not be started")) }
            return
        }
        try {
            val started = awaitListening(ports.first(), core)
            if (!started) {
                core.stop()
                if (splitOnFailure && batch.size > 1) {
                    // One config the core refuses keeps the whole batch from starting.
                    batch.forEachIndexed { i, item ->
                        runBatch(listOf(item), "$tag-$i", launcher, networkDns, gate, onResult, freePorts, false)
                    }
                } else {
                    batch.forEach { onResult(it.id, Result.Invalid("Xray refused this config")) }
                }
                return
            }
            coroutineScope {
                entries.map { (item, port) ->
                    async(Dispatchers.IO) {
                        gate.withPermit { onResult(item.id, measurePort(port)) }
                    }
                }.awaitAll()
            }
        } finally {
            core.stop()
        }
    }

    /** Two rounds through one inbound; the faster successful one is the delay. */
    internal fun measurePort(
        port: Int,
        timeoutMs: Int = REQUEST_TIMEOUT_MS,
        user: String = probeUser,
        pass: String = probePass,
    ): Result {
        var best = -1L
        var lastError = "no reply"
        for (round in 0 until ROUNDS) {
            var answered = false
            for (target in TARGETS) {
                try {
                    val ms = requestThrough(port, target, timeoutMs, user, pass)
                    best = if (best < 0) ms else minOf(best, ms)
                    answered = true
                    break
                } catch (e: IOException) {
                    lastError = describe(e)
                    // A timeout already spent the whole budget; another target
                    // would only double the wait for a config that carries nothing.
                    if (e is java.net.SocketTimeoutException) break
                }
            }
            if (!answered && best < 0) return Result.NoReply(lastError)
        }
        return if (best >= 0) Result.Ok(best.coerceAtLeast(1L)) else Result.NoReply(lastError)
    }

    /**
     * One `GET /generate_204` through the SOCKS5 inbound at [port].
     *
     * The clock starts after the local SOCKS handshake (Xray answers that itself,
     * before dialling anything) and stops at the first response line, so the
     * number is the config's own cost: its handshake and one round trip.
     */
    internal fun requestThrough(
        port: Int,
        host: String,
        timeoutMs: Int,
        user: String = probeUser,
        pass: String = probePass,
    ): Long {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(LOOPBACK, port), LOCAL_CONNECT_MS)
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            // Offer username/password only (RFC 1929): the probe inbound requires it.
            out.write(byteArrayOf(5, 1, 2))
            out.flush()
            val greeting = input.readExactly(2)
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != 2) throw IOException("socks greeting refused")
            val u = user.toByteArray(Charsets.US_ASCII)
            val p = pass.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            out.flush()
            val auth = input.readExactly(2)
            if (auth[1].toInt() != 0) throw IOException("socks authentication refused")

            val name = host.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(0, 80))
            out.flush()
            val head = input.readExactly(4)
            if (head[1].toInt() != 0) throw IOException("socks connect failed (${head[1].toInt()})")
            when (head[3].toInt()) {
                1 -> input.readExactly(4 + 2)
                4 -> input.readExactly(16 + 2)
                3 -> input.readExactly((input.read().also { if (it < 0) throw EOFException() }) + 2)
                else -> throw IOException("socks reply malformed")
            }

            val started = System.nanoTime()
            out.write(
                ("GET $TARGET_PATH HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\n" +
                    "Accept: */*\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()
            val status = input.readLine(MAX_STATUS_LINE)
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            if (!status.startsWith("HTTP/")) throw IOException("not an HTTP reply")
            return elapsed
        }
    }

    private suspend fun awaitListening(port: Int, core: Core): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!core.isAlive()) return false
            val open = withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 300) }
                    true
                }.getOrDefault(false)
            }
            if (open) return true
            delay(100)
        }
        return false
    }

    /** Ports the OS reports free right now, all different. */
    fun freeLoopbackPorts(count: Int): List<Int> {
        val sockets = (0 until count).map { ServerSocket(0, 1, java.net.InetAddress.getByName(LOOPBACK)) }
        return try {
            sockets.map { it.localPort }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private fun describe(e: IOException): String = when (e) {
        is java.net.SocketTimeoutException -> "timed out"
        is EOFException -> "closed by the server"
        else -> e.message?.take(80) ?: e.javaClass.simpleName
    }

    private fun InputStream.readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    private fun InputStream.readLine(max: Int): String {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = read()
            if (c < 0) {
                if (sb.isEmpty()) throw EOFException()
                break
            }
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private const val ROUNDS = 2
    private const val LOOPBACK = "127.0.0.1"
    private const val LOCAL_CONNECT_MS = 1_000
    private const val MAX_STATUS_LINE = 256
    private const val INBOUND_PREFIX = "ping-in-"
    private const val OUTBOUND_PREFIX = "ping-out-"
}
