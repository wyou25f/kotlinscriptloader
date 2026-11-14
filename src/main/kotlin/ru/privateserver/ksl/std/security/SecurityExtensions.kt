@file:Suppress("unused")

package ru.privateserver.ksl.std.security

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security helpers for KSL scripts.
 *
 * Password hashing is deliberately separate from fast SHA-256 checksums.
 * Run password hashing and large-file hashing asynchronously, never on the
 * Bukkit server thread.
 */
object KSLSecurity {
    private const val PASSWORD_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val PASSWORD_FORMAT = "pbkdf2-sha256"
    private const val DEFAULT_ITERATIONS = 210_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 2_000_000
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private val random = SecureRandom()

    /** SHA-256 digest of UTF-8 text, returned as lowercase hexadecimal. */
    @JvmStatic
    fun sha256(value: String): String = digest("SHA-256", value.toByteArray(Charsets.UTF_8)).toHex()

    /** SHA-256 digest of a file, streamed to avoid loading the whole file into memory. */
    @JvmStatic
    fun sha256File(file: File): String {
        require(file.isFile) { "Not a regular file: ${file.path}" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    /** Checks a SHA-256 hex digest using constant-time byte comparison. */
    @JvmStatic
    fun verifySha256(value: String, expectedHex: String): Boolean {
        val expected = expectedHex.hexToBytesOrNull() ?: return false
        val actual = digest("SHA-256", value.toByteArray(Charsets.UTF_8))
        return expected.size == actual.size && MessageDigest.isEqual(actual, expected)
    }

    /**
     * Creates a salted PBKDF2-HMAC-SHA256 password hash.
     * Store the returned encoded string, not the original password.
     */
    @JvmStatic
    @JvmOverloads
    fun hashPassword(password: CharArray, iterations: Int = DEFAULT_ITERATIONS): String {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) {
            "PBKDF2 iterations must be between $MIN_ITERATIONS and $MAX_ITERATIONS"
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derivePassword(password, salt, iterations)
        return listOf(
            PASSWORD_FORMAT,
            iterations.toString(),
            Base64.getEncoder().withoutPadding().encodeToString(salt),
            Base64.getEncoder().withoutPadding().encodeToString(derived)
        ).joinToString("$")
    }

    /** String convenience overload. Prefer CharArray when handling sensitive input. */
    @JvmStatic
    @JvmOverloads
    fun hashPassword(password: String, iterations: Int = DEFAULT_ITERATIONS): String =
        hashPassword(password.toCharArray(), iterations)

    /** Verifies a password against a value produced by [hashPassword]. */
    @JvmStatic
    fun verifyPassword(password: CharArray, encodedHash: String): Boolean {
        if (password.isEmpty()) return false
        val parts = encodedHash.split('$')
        if (parts.size != 4 || parts[0] != PASSWORD_FORMAT) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return false
        val salt = decodeBase64(parts[2]) ?: return false
        val expected = decodeBase64(parts[3]) ?: return false
        if (salt.size !in 16..64 || expected.size != HASH_BYTES) return false
        val actual = derivePassword(password, salt, iterations)
        return MessageDigest.isEqual(actual, expected)
    }

    /** String convenience overload for [verifyPassword]. */
    @JvmStatic
    fun verifyPassword(password: String, encodedHash: String): Boolean =
        verifyPassword(password.toCharArray(), encodedHash)

    /** HMAC-SHA256 for message authentication; key material must remain secret. */
    @JvmStatic
    fun hmacSha256(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    /** Compares two strings without an early exit based on the first differing byte. */
    @JvmStatic
    fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

    /** Standard Base64 encoding of UTF-8 text. This is encoding, not encryption. */
    @JvmStatic
    fun base64Encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    /** Decodes standard Base64 as UTF-8, or returns null for malformed input. */
    @JvmStatic
    fun base64Decode(value: String): String? = runCatching {
        Base64.getDecoder().decode(value).toString(Charsets.UTF_8)
    }.getOrNull()

    /** Generates cryptographically secure random bytes encoded as URL-safe Base64. */
    @JvmStatic
    fun randomToken(byteCount: Int = 32): String {
        require(byteCount in 16..256) { "Token byte count must be between 16 and 256" }
        val bytes = ByteArray(byteCount).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun derivePassword(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, HASH_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(PASSWORD_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun digest(algorithm: String, bytes: ByteArray): ByteArray =
        MessageDigest.getInstance(algorithm).digest(bytes)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length != 64 || length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }

    private fun decodeBase64(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()
}

/** Concise top-level aliases for common KSL script use. */
fun String.sha256(): String = KSLSecurity.sha256(this)
fun File.sha256(): String = KSLSecurity.sha256File(this)
fun String.sha256Matches(expectedHex: String): Boolean = KSLSecurity.verifySha256(this, expectedHex)
fun hashPassword(password: String): String = KSLSecurity.hashPassword(password)
fun verifyPassword(password: String, encodedHash: String): Boolean =
    KSLSecurity.verifyPassword(password, encodedHash)
fun hmacSha256(message: String, secret: String): String = KSLSecurity.hmacSha256(message, secret)
fun secureToken(byteCount: Int = 32): String = KSLSecurity.randomToken(byteCount)
fun base64Encode(value: String): String = KSLSecurity.base64Encode(value)
fun base64Decode(value: String): String? = KSLSecurity.base64Decode(value)
