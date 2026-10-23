package studio.cluvex.aether.transport

import studio.cluvex.aether.core.NetProbe

/**
 * Exit countries offered for the chained Psiphon backend.
 *
 * The list is Psiphon's published egress regions. Psiphon treats the choice as a
 * HARD filter (see PsiphonTransport, which falls back to an automatic exit rather
 * than hanging forever). It was shared with the Tor backend until that backend was
 * removed; the codes are unchanged, so a saved exit country still resolves.
 *
 * Every label carries the country's flag emoji. The flag is derived from the ISO
 * 3166-1 alpha-2 code with [NetProbe.flagEmoji] instead of being shipped as an
 * image set: regional-indicator pairs are rendered by the system emoji font, so
 * the list stays in sync with the codes automatically and costs no APK size.
 * "Automatic" gets a globe rather than a blank space so the row is never the odd
 * one out visually.
 */
object ExitRegions {
    /** "" = automatic. Kept first so it stays the default row in the dropdown. */
    val values = listOf(
        "",
        "AE", "AR", "AT", "AU", "BE", "BG", "BR", "CA", "CH", "CL", "CO", "CY",
        "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HK", "HR", "HU",
        "IE", "IL", "IN", "IS", "IT", "JP", "KR", "LT", "LU", "LV", "MD", "MX",
        "MY", "NL", "NO", "NZ", "PH", "PL", "PT", "RO", "RS", "SE", "SG", "SK",
        "TH", "TR", "TW", "UA", "US", "VN", "ZA",
    )

    private val names = mapOf(
        "" to "Automatic",
        "AE" to "United Arab Emirates", "AR" to "Argentina", "AT" to "Austria",
        "AU" to "Australia", "BE" to "Belgium", "BG" to "Bulgaria",
        "BR" to "Brazil", "CA" to "Canada", "CH" to "Switzerland",
        "CL" to "Chile", "CO" to "Colombia", "CY" to "Cyprus",
        "CZ" to "Czechia", "DE" to "Germany", "DK" to "Denmark",
        "EE" to "Estonia", "ES" to "Spain", "FI" to "Finland",
        "FR" to "France", "GB" to "United Kingdom", "GR" to "Greece",
        "HK" to "Hong Kong", "HR" to "Croatia", "HU" to "Hungary",
        "IE" to "Ireland", "IL" to "Israel", "IN" to "India",
        "IS" to "Iceland", "IT" to "Italy", "JP" to "Japan",
        "KR" to "South Korea", "LT" to "Lithuania", "LU" to "Luxembourg",
        "LV" to "Latvia", "MD" to "Moldova", "MX" to "Mexico",
        "MY" to "Malaysia", "NL" to "Netherlands", "NO" to "Norway",
        "NZ" to "New Zealand", "PH" to "Philippines", "PL" to "Poland",
        "PT" to "Portugal", "RO" to "Romania", "RS" to "Serbia",
        "SE" to "Sweden", "SG" to "Singapore", "SK" to "Slovakia",
        "TH" to "Thailand", "TR" to "Turkey", "TW" to "Taiwan",
        "UA" to "Ukraine", "US" to "United States", "VN" to "Vietnam",
        "ZA" to "South Africa",
    )

    /** Globe emoji used for the "Automatic" row. */
    private const val GLOBE = "\uD83C\uDF10"

    /** Country name without a flag (for logs, where emoji are noise). */
    fun name(code: String): String {
        val cc = code.trim().uppercase()
        return names[cc] ?: cc.ifEmpty { "Automatic" }
    }

    /** Flag + country name, e.g. "\uD83C\uDDE9\uD83C\uDDEA  Germany". */
    fun label(code: String): String {
        val cc = code.trim().uppercase()
        val badge = if (cc.isEmpty()) GLOBE else NetProbe.flagEmoji(cc)
        return "$badge  ${name(cc)}"
    }
}
