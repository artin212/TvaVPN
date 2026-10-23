package studio.cluvex.aether.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one function in the AI feature that protects the user rather than
 * the app.
 *
 * This file exists because of a bug found while writing it. The first version of
 * the IPv6 rule was "two or more hex groups separated by colons", which is a
 * correct-looking pattern that also matches `10:23:41` - the wall-clock timestamp
 * at the start of every line this app logs. It would have redacted the ordering
 * and timing out of every digest sent to the advisor, silently, producing worse
 * advice from a log that still looked fine on inspection.
 *
 * A redaction function has two failure modes and both are invisible: too little
 * and it publishes the user, too much and it destroys the evidence. Neither shows
 * up by using the app. They show up here.
 */
class AiRedactionTest {

    // ---- the crash this file failed to catch ------------------------------

    /**
     * The 1.2.9 field crash, as a test.
     *
     * `redactLine` asked `Regex.replace` for `groupValues[1]` on the IPv6 pattern,
     * which is written entirely with non-capturing groups - so every line that
     * contained an IPv6 literal threw `IndexOutOfBoundsException: No group 1`
     * instead of being redacted. The engine prints such a line on EVERY connect
     * (`[+] identity ready: ... ipv6=2606:...`), the throw escaped the coroutine
     * that builds the digest, and the process died a minute or two after
     * connecting.
     *
     * Every assertion above this one would have failed the same way, which is the
     * real lesson: these tests were never run against the shipped build. This one
     * is deliberately the cheapest possible check - "it returns" - so it fails
     * loudly and unambiguously if the group assumption ever comes back.
     */
    @Test
    fun `a line with an ipv6 literal is redacted instead of throwing`() {
        val forms = listOf(
            "ipv6=2606:4700:110:82e2:10c3:d77f:6bfb:e0b6",
            "peer 2606:4700::1 selected",
            "bound ::",
            "bound ::1",
            "fe80::1%wlan0 is the local link",
            "mapped ::ffff:203.0.113.9 seen",
            "[2606:4700:110:82e2:10c3:d77f:6bfb:e0b6]:443 handshake ok",
        )
        for (line in forms) {
            // The assertion is that this call RETURNS. A throw here is the bug.
            val out = AiRedaction.redactLine(line)
            assertFalse("interface identifier survived: $out", out.contains("6bfb:e0b6"))
        }
    }

    /**
     * An IPv4-mapped IPv6 literal is one address, and is masked like an address.
     *
     * The v6 rules used to match `::ffff:203` and stop, leaving `.0.113.9` sitting
     * in the digest as loose text - three octets of the public address the rule
     * exists to hide. The mapped forms are matched whole and get the v4 policy,
     * including "private stays whole".
     */
    @Test
    fun `ipv4 mapped v6 addresses are masked as addresses`() {
        assertEquals(
            "mapped ::ffff:203.0.x.x seen",
            AiRedaction.redactLine("mapped ::ffff:203.0.113.9 seen"),
        )
        assertEquals(
            "peer 0:0:0:0:0:ffff:198.51.x.x up",
            AiRedaction.redactLine("peer 0:0:0:0:0:ffff:198.51.100.7 up"),
        )
        assertEquals(
            "mapped ::ffff:127.0.0.1 local",
            AiRedaction.redactLine("mapped ::ffff:127.0.0.1 local"),
        )
    }

    /**
     * Psiphon writes its notices as JSON, so a labelled-field rule has to
     * understand `"sessionId":"..."` and not only `sessionId=...`.
     */
    @Test
    fun `json shaped identifiers and credentials are redacted too`() {
        val line = "12:40:09.215 I/Transport: Psiphon: SessionId: " +
            "{\"sessionId\":\"5a4c3a09495f6de4c08995188cda7799\"}"
        assertFalse(AiRedaction.redactLine(line).contains("5a4c3a09495f6de4c08995188cda7799"))
        assertFalse(
            AiRedaction.redactLine("{\"api_key\":\"AIzaSyDummyKeyValue123456\"}").contains("AIza"),
        )
    }

    /**
     * The same thing one level up: a whole realistic session log through [digest].
     *
     * `digest` is what the advisor actually calls, on a log that mixes engine
     * lines, Psiphon JSON notices, Rust module paths (`aether::wg_prober`) and
     * wall-clock timestamps. It must come back with the diagnostic shape intact
     * and no public address in it.
     */
    @Test
    fun `a realistic session log digests without throwing and without public addresses`() {
        val log = listOf(
            "12:39:53.987 I/build: APK patch level 1.2.9 (version 1.2.9, code 13003, core 1.9.0)",
            "12:39:54.020 D/engine: [INFO aether] [+] identity ready: " +
                "device=48095616-ea3a-4d95-b5b5-e36093297691 ipv4=172.16.0.2 " +
                "ipv6=2606:4700:110:82e2:10c3:d77f:6bfb:e0b6",
            "12:39:54.395 D/engine: [INFO aether::wg_prober] [*] wireguard scan mode=balanced",
            "12:40:07.313 D/engine: [INFO aether] [+] selected WireGuard endpoint 188.114.97.109:934",
            "12:40:08.078 D/engine: [INFO aether::socks] socks5 listening on 127.0.0.1:1819",
            "12:40:09.215 I/Transport: Psiphon: SessionId: " +
                "{\"sessionId\":\"5a4c3a09495f6de4c08995188cda7799\"}",
            "12:40:10.365 I/Transport: Psiphon: NetworkID: {\"ID\":\"WIFI+[redacted]\"}",
        ).joinToString("\n")

        val digest = AiRedaction.digest(log, ownApiKey = "")

        // Nothing that names the user or their subscriber address.
        assertFalse(digest.contains("48095616"))
        assertFalse(digest.contains("6bfb:e0b6"))
        assertFalse(digest.contains("188.114.97.109"))
        assertFalse(digest.contains("5a4c3a09495f6de4c08995188cda7799"))
        // Everything that makes the log diagnosable.
        assertTrue(digest.contains("188.114.x.x:934"))
        assertTrue(digest.contains("127.0.0.1:1819"))
        assertTrue(digest.contains("aether::wg_prober"))
        assertTrue(digest.contains("12:40:07.313"))
        assertTrue(digest.contains("patch level 1.2.9"))
    }

    // ---- what must be removed --------------------------------------------

    @Test
    fun `warp identity line loses the device id and the v6 interface id`() {
        val line = "[+] identity ready: device=c79b496b-d123-4e32-851e-7fddc4be55a2 " +
            "ipv4=172.16.0.2 ipv6=2606:4700:110:8cf5:d172:c495:8919:3df5"
        val out = AiRedaction.redactLine(line)

        assertFalse("the enrolment id must not leave the device", out.contains("c79b496b"))
        assertFalse("the v6 interface id must not leave the device", out.contains("8919:3df5"))
        // The routing prefix is kept: it is what makes "the same edge family kept
        // failing" a readable conclusion.
        assertTrue(out.contains("2606:4700:x:x"))
        // 172.16/12 is private and diagnostic, so it stays whole.
        assertTrue(out.contains("172.16.0.2"))
    }

    @Test
    fun `public v4 endpoints are masked to a 16`() {
        assertEquals(
            "[+] wg endpoint 162.159.x.x:891 rtt=381ms",
            AiRedaction.redactLine("[+] wg endpoint 162.159.195.197:891 rtt=381ms"),
        )
    }

    @Test
    fun `credentials and bare uuids go wherever they appear`() {
        assertFalse(AiRedaction.redactLine("api_key: AIzaSyDummyKeyValue123456").contains("AIza"))
        assertFalse(
            AiRedaction.redactLine("diag 3f2504e0-4f89-11d3-9a0c-0305e82c3301 failed")
                .contains("3f2504e0"),
        )
    }

    @Test
    fun `the users own gemini key is removed even when it looks like nothing`() {
        val key = "not-an-aiza-shaped-key-at-all"
        val digest = AiRedaction.digest("a line containing $key here", ownApiKey = key)
        assertFalse(digest.contains(key))
    }

    // ---- what must survive -----------------------------------------------

    @Test
    fun `log timestamps are not mistaken for addresses`() {
        val line = "10:23:41.842 I/vpn: Waiting for SOCKS5 on 127.0.0.1:1819 (timeout=60s)"
        // Unchanged, entirely: the timestamp, and the loopback address that is the
        // single most diagnostic string in the whole log.
        assertEquals(line, AiRedaction.redactLine(line))
    }

    @Test
    fun `engine iso timestamps and tls group lists are not mistaken for addresses`() {
        val iso = "[2026-09-08T06:53:41.848Z INFO  aether] Aether v1.9.0"
        assertEquals(iso, AiRedaction.redactLine(iso))
        assertEquals("tlsGroups = X25519:P-256", AiRedaction.redactLine("tlsGroups = X25519:P-256"))
    }

    @Test
    fun `loopback and link local v6 stay whole`() {
        assertEquals("bound ::1", AiRedaction.redactLine("bound ::1"))
        assertEquals("iface fe80::1%wlan0", AiRedaction.redactLine("iface fe80::1%wlan0"))
    }

    // ---- the digest itself ------------------------------------------------

    @Test
    fun `digest keeps the tail and respects the cap`() {
        val log = (1..500).joinToString("\n") { "line $it" }
        val digest = AiRedaction.digest(log, ownApiKey = "")
        assertTrue("the tail is the useful end of a log", digest.contains("line 500"))
        assertFalse("the head is a previous session", digest.contains("line 1\n"))
        assertTrue(digest.length <= AiRedaction.MAX_DIGEST_CHARS)
    }
}
