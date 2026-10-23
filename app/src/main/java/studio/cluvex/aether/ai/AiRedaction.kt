package studio.cluvex.aether.ai

/**
 * Scrubs a diagnostics excerpt before it is sent to Google.
 *
 * ## Why a log cannot just be forwarded
 *
 * The DPI-optimisation feature works by showing the model what the connection log
 * says. That log is written by a circumvention tool for users under surveillance,
 * and it contains, at various verbosity levels: the endpoint addresses the scan
 * settled on, the user's own operator IP as reported by the geolocation probe,
 * Zero Trust enrolment identifiers, and - the moment somebody pastes one into a
 * field - a credential. Handing that verbatim to a third-party API would take
 * data that exists only to help the user debug their tunnel and publish the
 * user's network identity with it.
 *
 * So the digest that leaves the device is filtered, not merely truncated:
 *
 *  - anything that looks like a credential is replaced wholesale, including the
 *    user's own Gemini key (which would otherwise be echoed back through a
 *    request authenticated WITH that key);
 *  - public IPv4 literals are masked to their /16 and public IPv6 literals to
 *    their /32, which is enough for the model to reason about "the same edge kept
 *    failing" while not being enough to identify a subscriber. Private, loopback
 *    and link-local ranges are kept intact in both families because
 *    `127.0.0.1:1819` is the single most diagnostic string in the whole log;
 *  - installation-scoped identifiers - the WARP `device=` enrolment handle, and
 *    any bare UUID - are removed, because they name this install permanently and
 *    survive every address change;
 *  - the digest is capped, so a chatty session cannot silently push a megabyte of
 *    history off the device.
 */
object AiRedaction {

    /** Hard cap on the characters of log we are willing to send. */
    const val MAX_DIGEST_CHARS = 12_000

    /**
     * A labelled credential, in `key: value`, `key=value` AND `"key":"value"` form.
     *
     * The optional quotes are not cosmetic. Half of this app's log is Psiphon's
     * JSON notices, so a rule that only understands bare labels reads
     * `{"sessionId":"5a4c..."}` as ordinary prose and forwards it verbatim. Same
     * rule, same reason, in [DEVICE_FIELD].
     */
    private val CREDENTIAL_LINE = Regex(
        "(?i)(key|token|secret|password|passwd|authorization|bearer|client[_-]?id)" +
            "\"?\\s*[:=]\\s*\"?\\S+",
    )

    /** Google API keys have a fixed, recognisable shape; kill them anywhere. */
    private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_\\-]{10,}")

    private val IPV4 = Regex("\\b((?:\\d{1,3}\\.){3}\\d{1,3})\\b")

    // ------------------------------------------------------------------------
    // SECURITY FIX (1.2.9 audit of the AI path).
    //
    // The digest masked IPv4 and nothing else, so this line - which the engine
    // writes on EVERY connect at debug verbosity, and which is therefore in the
    // tail of the log the advisor sends -
    //
    //   [+] identity ready: device=c79b496b-d123-4e32-851e-7fddc4be55a2
    //       ipv4=172.16.0.2 ipv6=2606:4700:110:8cf5:d172:c495:8919:3df5
    //
    // left the device intact apart from the RFC1918 v4 address. Two problems, both
    // worse than the v4 leak this file was written to stop:
    //
    //  * the IPv6 is the user's WARP-assigned address. It is globally routable, it
    //    is stable across sessions because it is stored in aether.toml, and a /128
    //    is not a subscriber-sized guess - it IS the subscriber. Masking v4 to a
    //    /16 while shipping a full v6 address is not partial protection, it is
    //    none.
    //  * `device=` is the WARP enrolment identifier: a permanent handle for this
    //    installation, and it survives an IP change. CREDENTIAL_LINE did not match
    //    it because the word is "device", not "key" or "token".
    //
    // Both now go before the text leaves the device. IPv6 is masked to its /32
    // routing prefix, which keeps "the same Cloudflare edge family kept failing"
    // readable while dropping the interface identifier that names the user.
    // ------------------------------------------------------------------------

    /**
     * An IPv6 literal, in either the full or the compressed form.
     *
     * The two alternatives are the whole point, and a looser pattern is NOT
     * acceptable here even though this file normally prefers over-redaction.
     * A rule like "two or more hex groups separated by colons" also matches
     * `10:23:41`, which is the wall-clock timestamp at the start of EVERY line of
     * this app's log. Redacting those would strip the ordering and the timing out
     * of the digest - the two things the advisor reasons about most - and would do
     * it silently, producing worse advice from a log that still looked fine.
     *
     * So: either all eight groups spelled out, or a run that actually contains the
     * `::` elision. There is no valid IPv6 text form that is neither, and neither
     * alternative can match `HH:MM:SS`.
     */
    private val IPV6 = Regex(
        // IPv4-mapped/compatible forms FIRST, so the whole literal - colons AND
        // dotted quad - is one match. Alternation is leftmost-first, so if the
        // general rules below came first they would match `::ffff:203` and leave
        // `.0.113.9` behind as loose text: three octets of a public address,
        // published by the rule that exists to mask it. Kept deliberately tight
        // (`::` or an all-zero prefix) so no `HH:MM:SS.mmm` timestamp and no
        // `1.2.9` version string can reach it.
        "::(?:[Ff]{4}:)?(?:\\d{1,3}\\.){3}\\d{1,3}" +
            "|(?:0{1,4}:){4,5}[Ff]{4}:(?:\\d{1,3}\\.){3}\\d{1,3}" +
            // Full form: exactly eight hextets.
            "|(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}" +
            // Compressed, with something before the elision: 2606:4700::1
            "|(?:[0-9A-Fa-f]{1,4}:){1,7}:(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?" +
            // Compressed, starting at the elision: ::1, ::ffff:...
            "|::(?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?",
    )

    /**
     * Installation-scoped identifiers: `device=`, `deviceId`, `identity`, and bare
     * UUIDs anywhere.
     *
     * The bare-UUID rule is the important half: a session id, a diagnostic id or an
     * enrolment handle printed without a recognisable label is exactly the string
     * that a labelled-field regex misses, and every one of them is a stable name
     * for this installation.
     */
    private val DEVICE_FIELD = Regex(
        "(?i)(?<![A-Za-z0-9_])\"?" +
            "(device(?:[_-]?id)?|installation(?:[_-]?id)?|identity|session[_-]?id)" +
            "\"?\\s*[:=]\\s*\"?\\S+",
    )

    private val UUID = Regex(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
    )

    /**
     * Builds the digest: the most recent [maxLines] lines, redacted and capped.
     *
     * The TAIL, not the head: when a connection misbehaves, the useful evidence is
     * what happened last, and the head of a restored log is a previous session.
     */
    fun digest(fullLog: String, ownApiKey: String, maxLines: Int = 220): String {
        val tail = fullLog.lineSequence().toList().takeLast(maxLines)
        val cleaned = tail.joinToString("\n") { safeRedactLine(it) }
        val withoutOwnKey =
            if (ownApiKey.isBlank()) cleaned else cleaned.replace(ownApiKey, "[REDACTED]")
        return if (withoutOwnKey.length <= MAX_DIGEST_CHARS) {
            withoutOwnKey
        } else {
            withoutOwnKey.substring(withoutOwnKey.length - MAX_DIGEST_CHARS)
        }
    }

    /**
     * Redacts EVERY line of [text], with no line cap and no length cap.
     *
     * [digest] exists to build a small, bounded payload for a model. This exists
     * for the other direction: the user's own "copy log" button, where the whole
     * log is wanted but the identity in it is not (see
     * [studio.cluvex.aether.core.DiagnosticsLog.exportRedactedText]). Same rules,
     * same per-line failure containment - a line that throws is dropped, never
     * emitted raw.
     */
    fun redactAll(text: String, ownApiKey: String = ""): String {
        val cleaned = text.lineSequence().joinToString("\n") { safeRedactLine(it) }
        return if (ownApiKey.isBlank()) cleaned else cleaned.replace(ownApiKey, "[REDACTED]")
    }

    /**
     * Redacts one line: credentials, then identifiers, then addresses.
     *
     * Order matters. Credentials go first because a credential that happens to
     * contain a colon run would otherwise be partly eaten by the IPv6 rule and
     * partly survive. IPv6 goes before IPv4 because an IPv4-mapped v6 address
     * (`::ffff:1.2.3.4`) has to be handled as one address, not as a colon run with
     * a dotted quad left behind it.
     */
    fun redactLine(line: String): String {
        var out = GOOGLE_KEY.replace(line, "[REDACTED]")
        out = CREDENTIAL_LINE.replace(out) { m -> "${m.label()}=[REDACTED]" }
        out = DEVICE_FIELD.replace(out) { m -> "${m.label()}=[REDACTED]" }
        out = UUID.replace(out, "[REDACTED-ID]")
        // ------------------------------------------------------------------
        // CRASH FIX (1.2.9): these two used `m.groupValues[1]`.
        //
        // IPV6 is written entirely with NON-capturing groups - `(?:...)` - on
        // purpose, because its three alternatives are alternatives, not fields.
        // So group 1 does not exist, and asking for it threw
        //
        //   java.lang.IndexOutOfBoundsException: No group 1
        //     at kotlin.text.MatcherMatchResult$groupValues$1.get(Regex.kt:382)
        //     at ...AiRedaction.redactLine(AiRedaction.kt:149)
        //
        // on the FIRST log line that contained any IPv6 literal. The engine
        // prints one on every connect (`[+] identity ready: ... ipv6=2606:...`),
        // the throw escaped `Regex.replace` into the coroutine that builds the
        // digest, and nothing above it caught it - so the process died a minute
        // or two after connecting, every time, in both single and chained mode.
        //
        // What a redaction rule wants here is always the WHOLE match: the text
        // that was recognised as an address is the text that must be masked.
        // `m.value` is group 0, which every match has, so this can no longer
        // depend on how the pattern happens to be bracketed.
        // ------------------------------------------------------------------
        out = IPV6.replace(out) { m -> maskIpv6(m.value) }
        return IPV4.replace(out) { m -> maskIpv4(m.value) }
    }

    /**
     * The label a `key: value` rule matched, without assuming the group is there.
     *
     * `groupValues[1]` throws when the pattern has no capture group; `getOrElse`
     * checks the size first. A missing label degrades to a bare `[REDACTED]`,
     * which is the safe direction: the secret is still gone.
     */
    private fun MatchResult.label(): String =
        groupValues.getOrElse(1) { "" }.ifBlank { "field" }

    /**
     * [redactLine] with a hard guarantee that it cannot take the process down.
     *
     * A redactor is called on attacker-influenced text (server names, SNI values,
     * pasted config) from a background coroutine whose only exception handler is
     * the one that kills the app. The bug above proves the cost: one unmatched
     * assumption inside a `Regex.replace` lambda was a reliable crash on connect.
     *
     * If any line ever throws again, that LINE is dropped - replaced by a marker,
     * never by its raw content, because falling back to the unredacted text would
     * turn a crash into a data leak - and the rest of the digest still goes out.
     */
    private fun safeRedactLine(line: String): String =
        try {
            redactLine(line)
        } catch (t: Throwable) {
            "[REDACTION-FAILED-LINE-DROPPED]"
        }

    /**
     * Keeps the first two hextets of a public IPv6 and drops the rest.
     *
     * Two hextets is the /32 a provider is allocated, so `2606:4700:...` still
     * reads as Cloudflare and two failing endpoints in the same prefix are still
     * visibly related - which is the entire diagnostic value of an address in this
     * log. Everything after it, including the interface identifier that is unique
     * to this installation, goes.
     *
     * Loopback and link-local are kept whole for the same reason `127.0.0.1` is:
     * they are plumbing, not identity.
     */
    private fun maskIpv6(ip: String): String {
        val lower = ip.lowercase()
        if (lower == "::" || lower == "::1" || lower.startsWith("fe80:")) return ip
        // An IPv4-mapped address IS an IPv4 address wearing a v6 hat, so it gets
        // the v4 policy: the prefix stays, the host part goes, and a private or
        // loopback quad stays whole exactly as `127.0.0.1` does.
        if (lower.contains('.')) {
            val cut = lower.lastIndexOf(':')
            if (cut >= 0) {
                return lower.substring(0, cut + 1) + maskIpv4(lower.substring(cut + 1))
            }
        }
        // Unique-local (fc00::/7) is private address space, like 10.x.
        if (lower.startsWith("fc") || lower.startsWith("fd")) return ip
        val parts = lower.split(":")
        // A /32 needs two real leading hextets. An address that elides from the
        // front (`::1234`) has no prefix to keep, so it is masked whole rather
        // than half-described - a partially masked address is still an address.
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return "[REDACTED-IPV6]"
        return "${parts[0]}:${parts[1]}:x:x"
    }

    /**
     * Masks the host part of a public IPv4, keeps private/loopback ones whole.
     *
     * `127.0.0.1`, `10.x`, `192.168.x` and `172.16-31.x` are plumbing, not
     * identity, and they are what makes the log readable. Everything else becomes
     * `a.b.x.x`.
     */
    private fun maskIpv4(ip: String): String {
        val parts = ip.split(".")
        if (parts.size != 4) return ip
        val octets = parts.map { it.toIntOrNull() ?: return ip }
        if (octets.any { it > 255 }) return ip
        val private = octets[0] == 127 ||
            octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254) ||
            octets[0] == 0
        return if (private) ip else "${octets[0]}.${octets[1]}.x.x"
    }
}
