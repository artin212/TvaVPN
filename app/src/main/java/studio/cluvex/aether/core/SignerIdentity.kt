package studio.cluvex.aether.core

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import studio.cluvex.aether.BuildConfig
import java.io.File

/**
 * Answers the question the signing-key finding actually leaves the user with:
 * **is this copy of Aether the one the project published?**
 *
 * ## The finding this mitigates (F-1, CRITICAL, still open by design)
 *
 * `.github/ci-keystore.jks.b64` is the release signing key and it is committed to
 * the repository. Anyone who can read the repo can build an APK that Android
 * accepts as an in-place UPDATE of this app, with the same certificate and no
 * warning. The only real fix is a key rotation, and a rotation is exactly what
 * this release is forbidden from doing: the certificate is what every published
 * release was signed with, so replacing it would force every existing user to
 * uninstall first (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and lose their settings.
 *
 * What *can* be done without touching the key is to make the identity of a build
 * checkable, by the user, in the app, in two seconds:
 *
 *  * [fingerprint] is the SHA-256 of the certificate that actually signed the
 *    running APK, read from the system - not from anything inside the APK.
 *  * [expected] is the fingerprint the project publishes
 *    (`.github/expected-signer.txt`, or the CI signer when that is what the
 *    repository ships with), compiled in at build time.
 *  * [apkSha256] is the hash of the APK file itself, which CI now prints in the
 *    release body for every artifact.
 *
 * So: a build signed by a stranger's key is visibly a build signed by a stranger's
 * key (About shows a mismatch, and the diagnostics log carries an error line on
 * every launch), and a build signed with the project's key can be tied to a
 * specific published artifact by comparing one hash with the releases page.
 *
 * ## What this is NOT
 *
 * This is not tamper-proofing. An attacker who repackages the app can also patch
 * this class out; nothing running inside the APK can prove anything about the APK
 * to a determined local attacker. It is aimed at the realistic threat instead: a
 * *fake Aether* handed out through a Telegram channel or a mirror site, which is
 * how VPN users in censored networks actually get compromised. That build is not
 * signed with this certificate, and the honest signals above are what a user can
 * check before trusting it - together with the hash on the official release page,
 * which is outside the attacker's control.
 */
object SignerIdentity {

    private const val TAG = "signer"

    enum class Status {
        /** The APK is signed with the fingerprint this build expects. */
        MATCHES,

        /** Signed with something else: a repackaged or third-party build. */
        MISMATCH,

        /** This build has no pinned fingerprint compiled in (e.g. a local build). */
        UNPINNED,

        /** The platform would not tell us. Treated as unknown, never as OK. */
        UNKNOWN,
    }

    /** The fingerprint this build expects, lowercase hex, or "" when unpinned. */
    val expected: String = BuildConfig.EXPECTED_SIGNER.trim().lowercase()

    /**
     * How this APK was signed, as recorded by the build:
     * `release` (a private key from CI secrets), `test` / `public-ci` (the public
     * key committed to the repository) or `unsigned`.
     */
    val signingMode: String = BuildConfig.SIGNING_MODE

    /** SHA-256 of the signing certificate, lowercase hex; null if unavailable. */
    fun fingerprint(context: Context): String? {
        cachedFingerprint?.let { return it }
        val signatures = signatures(context) ?: return null
        val digests = signatures.mapNotNull { runCatching { LanGuard.sha256Hex(it.toByteArray()) }.getOrNull() }
        if (digests.isEmpty()) return null
        // Prefer the one that matches, so an app whose v3 rotation history contains
        // the pinned certificate is reported as matching rather than as a mismatch.
        val chosen = digests.firstOrNull { it == expected } ?: digests.first()
        cachedFingerprint = chosen
        return chosen
    }

    fun status(context: Context): Status {
        val actual = fingerprint(context) ?: return Status.UNKNOWN
        if (expected.length != 64) return Status.UNPINNED
        return if (actual == expected) Status.MATCHES else Status.MISMATCH
    }

    /**
     * One line in the diagnostics log per process, and a loud one on a mismatch.
     *
     * Called from `Application.onCreate`: reading the certificate is a single
     * PackageManager call and costs well under a millisecond, unlike [apkSha256].
     */
    fun logIdentity(context: Context) {
        if (logged) return
        logged = true
        val actual = fingerprint(context)
        when (status(context)) {
            Status.MATCHES -> DiagnosticsLog.i(
                TAG,
                "Signed with the expected Aether certificate (${short(actual)}), signing mode $signingMode.",
            )
            Status.MISMATCH -> DiagnosticsLog.e(
                TAG,
                "THIS BUILD IS NOT SIGNED BY THE AETHER PROJECT. Certificate ${short(actual)} " +
                    "does not match the published ${short(expected)}. A VPN app has full sight of " +
                    "your traffic - do not trust this copy, and reinstall from the official release page.",
            )
            Status.UNPINNED -> DiagnosticsLog.w(
                TAG,
                "No expected signer is compiled into this build, so its authenticity cannot be " +
                    "checked in-app. Certificate: ${short(actual)}.",
            )
            Status.UNKNOWN -> DiagnosticsLog.w(
                TAG,
                "The platform did not report a signing certificate for this package.",
            )
        }
    }

    /**
     * SHA-256 of the installed APK file, lowercase hex, or null.
     *
     * **Off the main thread only.** It streams 20-70 MB off flash; the About card
     * loads it in a coroutine on the IO dispatcher and caches the result for the
     * process. This is the value a user compares with the release page.
     */
    fun apkSha256(context: Context): String? {
        cachedApkHash?.let { return it }
        val path = context.applicationInfo.sourceDir ?: return null
        val digest = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            File(path).inputStream().buffered(1 shl 16).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        cachedApkHash = digest
        return digest
    }

    /** First and last four bytes, which is what people actually compare by eye. */
    fun short(hex: String?): String {
        if (hex.isNullOrBlank()) return "unknown"
        if (hex.length <= 20) return hex
        return hex.take(8) + "…" + hex.takeLast(8)
    }

    private fun signatures(context: Context): Array<Signature>? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                // The rotation history: for an app that has never rotated this is
                // exactly the one current certificate.
                signing.signingCertificateHistory ?: signing.apkContentsSigners
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
    }.getOrNull()

    @Volatile
    private var cachedFingerprint: String? = null

    @Volatile
    private var cachedApkHash: String? = null

    @Volatile
    private var logged = false
}
