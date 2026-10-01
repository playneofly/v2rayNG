package com.v2ray.ang.handler

import android.content.Context
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * FILTERNET: the locked "internal" area.
 *
 * ── why it is built this way ──────────────────────────────────────────────
 * The obvious design is to check the password against a server. That breaks
 * on exactly the day it matters most: during a national shutdown nothing
 * outside the country answers, so the gate would refuse everyone.
 *
 * So the password is never *checked* at all - it is the decryption key. The
 * private config bundle ships inside the APK, encrypted with AES-GCM under a
 * key derived from the password. A wrong password produces noise and GCM
 * refuses it; a right one produces the configs. No network, ever.
 *
 * Nothing stores the password: not the app, not GitHub. There is nothing to
 * find and nothing to leak.
 *
 * ── honest limit ──────────────────────────────────────────────────────────
 * Someone who knows the password can of course read the configs off their own
 * phone. No client-side lock can prevent that. The point is to stop the bundle
 * spreading to everyone who installs the app, which this does.
 */
object InternalVault {

    private const val ASSET = "internal.bin"
    private const val CACHE = "filternet_internal.bin"
    private const val KEY_UNLOCKED = "fn_internal_unlocked"

    private const val ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    /** Decrypted lines from the bundle, held only in memory. */
    @Volatile
    private var unlockedConfigs: List<String> = emptyList()

    @Volatile
    private var unlocked = false

    fun isUnlocked(): Boolean = unlocked

    fun configs(): List<String> = unlockedConfigs

    /** Remembered across launches so the user types the password once. */
    fun wasUnlockedBefore(): Boolean =
        runCatching { MmkvManager.decodeSettingsBool(KEY_UNLOCKED, false) }.getOrDefault(false)

    fun lock() {
        unlocked = false
        unlockedConfigs = emptyList()
        runCatching { MmkvManager.encodeSettings(KEY_UNLOCKED, false) }
    }

    /**
     * Tries to open the bundle with [password].
     *
     * @return true when the bundle decrypted, i.e. the password was right.
     */
    fun unlock(context: Context, password: String): Boolean {
        if (password.isBlank()) return false
        val blob = readBundle(context) ?: run {
            // No bundle shipped yet: accept the password so the internal tab is
            // still usable for the scanner, but with no private configs.
            return if (checkFallback(password)) {
                unlocked = true
                unlockedConfigs = emptyList()
                runCatching { MmkvManager.encodeSettings(KEY_UNLOCKED, true) }
                true
            } else {
                false
            }
        }
        val plain = decrypt(blob, password) ?: return false
        unlockedConfigs = plain.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("://") }
            .toList()
        unlocked = true
        runCatching { MmkvManager.encodeSettings(KEY_UNLOCKED, true) }
        LogUtil.i(AppConfig.TAG, "InternalVault: unlocked, ${unlockedConfigs.size} configs")
        return true
    }

    /**
     * Used only while no encrypted bundle has been shipped. Compares against a
     * salted hash so the password is still not readable in the binary.
     */
    private fun checkFallback(password: String): Boolean = runCatching {
        val key = deriveKey(password, FALLBACK_SALT)
        Base64.encodeToString(key.encoded, Base64.NO_WRAP) == FALLBACK_VERIFIER
    }.getOrDefault(false)

    /**
     * PBKDF2 over a fixed salt. Generated once with the chosen password; see
     * tools/make-internal-bundle.md for how to regenerate both values.
     */
    private val FALLBACK_SALT = byteArrayOf(
        0x46, 0x49, 0x4C, 0x54, 0x45, 0x52, 0x4E, 0x45,
        0x54, 0x2D, 0x76, 0x31, 0x2D, 0x73, 0x61, 0x6C,
    )

    /** Base64 of PBKDF2(password, FALLBACK_SALT, 200000, 256). */
    private const val FALLBACK_VERIFIER = "UmsjN+KGoBWqQs3RhaiQd9F/9KhccJiEaW8KtnC/fEk="

    /* ─────────────────────────── crypto ─────────────────────────── */

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Layout: [salt 16][iv 12][ciphertext + tag]. */
    private fun decrypt(blob: ByteArray, password: String): String? = runCatching {
        if (blob.size < SALT_LEN + IV_LEN + 16) return@runCatching null
        val salt = blob.copyOfRange(0, SALT_LEN)
        val iv = blob.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
        val body = blob.copyOfRange(SALT_LEN + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /** Only used by the packaging helper, kept here so both sides agree. */
    fun encrypt(plain: String, password: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(GCM_TAG_BITS, iv))
        return salt + iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    /* ─────────────────────────── storage ─────────────────────────── */

    /**
     * The bundle shipped in the APK, or a newer one cached from the network.
     * The cached copy wins when present, so the list can be refreshed without
     * a new release - and because it is encrypted, hosting it publicly is fine.
     */
    private fun readBundle(context: Context): ByteArray? {
        runCatching {
            val cached = File(context.filesDir, CACHE)
            if (cached.exists() && cached.length() > 0) return cached.readBytes()
        }
        return runCatching {
            context.assets.open(ASSET).use { it.readBytes() }
        }.getOrNull()
    }

    /** Stores a freshly downloaded bundle. Still encrypted on disk. */
    fun cacheBundle(context: Context, blob: ByteArray) = runCatching {
        File(context.filesDir, CACHE).writeBytes(blob)
    }.getOrNull()
}
