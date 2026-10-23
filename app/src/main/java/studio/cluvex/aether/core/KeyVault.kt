package studio.cluvex.aether.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The app's file-at-rest vault: one hardware-backed AES-256-GCM key, used to
 * seal FILES the app owns.
 *
 * ## Why this exists (audit 1.2.9-r3, findings F-2 and F-3)
 *
 * Two files inside the app sandbox were plaintext and both of them answer the
 * question "who was this user talking to":
 *
 *  * `diagnostics.log` - the endpoints the scan settled on, the exit IP, the WARP
 *    enrolment handle and the assigned IPv6, kept across launches on purpose so a
 *    crash stays inspectable;
 *  * the engine's `aether*.toml` identity files - the **WireGuard private key**
 *    (base64) and the WARP device identity.
 *
 * App-private storage and `allowBackup="false"` protect both against other apps
 * and against cloud/adb backup, which is why neither was ever critical. Neither
 * survives the threat model this app is actually shipped into: a rooted phone, a
 * seized phone, or a forensic image. On any of those, reading `/data/data/...` is
 * a `cat`.
 *
 * ## Design
 *
 * The key is generated inside the Android Keystore, marked non-exportable, and
 * NOT bound to user authentication - the VPN has to be able to reconnect
 * unattended (boot, network change), and a key that needs a keyguard unlock would
 * make an auto-reconnect impossible rather than making it safer. What it buys is
 * exactly the property that matters here: the ciphertext is worthless off this
 * device, and worthless on it without the OS keystore, because the raw key never
 * enters the app's address space.
 *
 * [studio.cluvex.aether.data.SecretStore] does the same thing for small
 * preference VALUES and is deliberately left alone: it has its own key alias, its
 * own SharedPreferences file and a different failure mode (a value it cannot
 * decrypt is dropped). This one is for whole files, so it hands the caller a
 * nullable result and lets the caller decide - see [EncryptedLogFile], which fails
 * CLOSED and stops mirroring to disk rather than writing plaintext.
 *
 * Layout of a sealed blob: `iv(12) || ciphertext||tag(16)`.
 */
object KeyVault {

    /** Length of the GCM IV that prefixes every sealed blob. */
    const val IV_LEN = 12

    /**
     * True when the keystore handed us a usable key. Callers that must never
     * fall back to plaintext check this first; it is also what the diagnostics
     * panel's one-time warning is keyed on.
     */
    val available: Boolean
        get() = key() != null

    /** Seals [plain]; null when no key is available (never a plaintext echo). */
    fun seal(plain: ByteArray): ByteArray? {
        val secret = key() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.iv + cipher.doFinal(plain)
        }.getOrNull()
    }

    /** Opens a blob produced by [seal]; null when it is not ours or is damaged. */
    fun open(blob: ByteArray): ByteArray? {
        if (blob.size <= IV_LEN) return null
        val secret = key() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secret,
                GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN),
            )
            cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        }.getOrNull()
    }

    /**
     * Loads (or creates, once) the vault key.
     *
     * Cached because both callers are on hot-ish paths: the log writer thread
     * seals a batch of lines several times a second while the engine is chatty,
     * and a `KeyStore.load` per batch would be pointless syscall traffic.
     *
     * A keystore that throws is remembered as broken instead of being retried on
     * every line: the failure is structural (no AndroidKeyStore provider, a
     * corrupted keystore, an OEM build that invalidated the key), so retrying
     * costs CPU and changes nothing.
     */
    private fun key(): SecretKey? {
        cached?.let { return it }
        if (broken) return null
        synchronized(this) {
            cached?.let { return it }
            if (broken) return null
            val secret = runCatching { loadOrCreate() }.getOrNull()
            if (secret == null) {
                broken = true
                return null
            }
            cached = secret
            return secret
        }
    }

    private fun loadOrCreate(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // See the class comment: deliberately NOT auth-bound, so an
                // unattended reconnect still works.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    @Volatile
    private var cached: SecretKey? = null

    @Volatile
    private var broken = false

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "aether_file_vault_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
}
