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
    private const val MAX_BASE64_DECODE_BYTES = 1_048_576
    private const val MAX_BASE64_ENCODED_CHARS = 4 * ((MAX_BASE64_DECODE_BYTES + 2) / 3)
    private val random = SecureRandom()

    /** SHA-256 digest of UTF-8 text, returned as lowercase hexadecimal. */
    @JvmStatic
    fun sha256(value: String): String = digest("SHA-256", value.toByteArray(Charsets.UTF_8)).toHex()

    /** SHA-512 digest of UTF-8 text, returned as lowercase hexadecimal. */
    @JvmStatic
    fun sha512(value: String): String = digest("SHA-512", value.toByteArray(Charsets.UTF_8)).toHex()

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

    /** SHA-512 digest of a file, streamed to avoid loading the whole file into memory. */
    @JvmStatic
    fun sha512File(file: File): String = hashFile(file, "SHA-512")

    private fun hashFile(file: File, algorithm: String): String {
        require(file.isFile) { "Not a regular file: ${file.path}" }
        val digest = MessageDigest.getInstance(algorithm)
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
        return try {
            expected.size == actual.size && MessageDigest.isEqual(actual, expected)
        } finally {
            expected.fill(0)
            actual.fill(0)
        }
    }

    /** Checks a SHA-512 hex digest using constant-time byte comparison. */
    @JvmStatic
    fun verifySha512(value: String, expectedHex: String): Boolean {
        val expected = expectedHex.hexToBytesOrNull(128) ?: return false
        val actual = digest("SHA-512", value.toByteArray(Charsets.UTF_8))
        return try {
            MessageDigest.isEqual(actual, expected)
        } finally {
            expected.fill(0)
            actual.fill(0)
        }
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
        val encodedSalt = Base64.getEncoder().withoutPadding().encodeToString(salt)
        val derived = derivePassword(password, salt, iterations)
        val encodedDerived = try {
            Base64.getEncoder().withoutPadding().encodeToString(derived)
        } finally {
            derived.fill(0)
        }
        return try {
            listOf(PASSWORD_FORMAT, iterations.toString(), encodedSalt, encodedDerived).joinToString("$")
        } finally {
            salt.fill(0)
        }
    }

    /** String convenience overload. Prefer CharArray when handling sensitive input. */
    @JvmStatic
    @JvmOverloads
    fun hashPassword(password: String, iterations: Int = DEFAULT_ITERATIONS): String {
        val characters = password.toCharArray()
        return try {
            hashPassword(characters, iterations)
        } finally {
            characters.fill('\u0000')
        }
    }

    /** Verifies a password against a value produced by [hashPassword]. */
    @JvmStatic
    fun verifyPassword(password: CharArray, encodedHash: String): Boolean {
        if (password.isEmpty()) return false
        if (encodedHash.length !in 80..256) return false
        val parts = encodedHash.split('$')
        if (parts.size != 4 || parts[0] != PASSWORD_FORMAT) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return false
        val salt = decodeBase64(parts[2]) ?: return false
        if (salt.size !in 16..64) {
            salt.fill(0)
            return false
        }
        val expected = decodeBase64(parts[3]) ?: run {
            salt.fill(0)
            return false
        }
        if (expected.size != HASH_BYTES) {
            salt.fill(0)
            expected.fill(0)
            return false
        }
        return try {
            val actual = derivePassword(password, salt, iterations)
            try {
                MessageDigest.isEqual(actual, expected)
            } finally {
                actual.fill(0)
            }
        } finally {
            salt.fill(0)
            expected.fill(0)
        }
    }

    /** String convenience overload for [verifyPassword]. */
    @JvmStatic
    fun verifyPassword(password: String, encodedHash: String): Boolean {
        val characters = password.toCharArray()
        return try {
            verifyPassword(characters, encodedHash)
        } finally {
            characters.fill('\u0000')
        }
    }

    /** HMAC-SHA256 for message authentication; key material must remain secret. */
    @JvmStatic
    fun hmacSha256(message: String, secret: String): String {
        val keyBytes = secret.toByteArray(Charsets.UTF_8)
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
            val output = mac.doFinal(messageBytes)
            return try {
                output.toHex()
            } finally {
                output.fill(0)
            }
        } finally {
            keyBytes.fill(0)
            messageBytes.fill(0)
        }
    }

    /** Verifies an HMAC-SHA256 value using constant-time comparison. */
    @JvmStatic
    fun verifyHmacSha256(message: String, secret: String, expectedHex: String): Boolean {
        val expected = expectedHex.hexToBytesOrNull() ?: return false
        val actual = hmacSha256(message, secret).hexToBytesOrNull() ?: run {
            expected.fill(0)
            return false
        }
        return try {
            MessageDigest.isEqual(actual, expected)
        } finally {
            expected.fill(0)
            actual.fill(0)
        }
    }

    /** Returns true when a stored PBKDF2 hash should be upgraded to the target work factor. */
    @JvmStatic
    @JvmOverloads
    fun needsPasswordRehash(encodedHash: String, targetIterations: Int = DEFAULT_ITERATIONS): Boolean {
        require(targetIterations in MIN_ITERATIONS..MAX_ITERATIONS) {
            "PBKDF2 iterations must be between $MIN_ITERATIONS and $MAX_ITERATIONS"
        }
        if (encodedHash.length !in 80..256) return true
        val parts = encodedHash.split('$')
        if (parts.size != 4 || parts[0] != PASSWORD_FORMAT) return true
        val iterations = parts[1].toIntOrNull() ?: return true
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return true
        val salt = decodeBase64(parts[2]) ?: return true
        val hash = decodeBase64(parts[3]) ?: return true
        return salt.size !in 16..64 || hash.size != HASH_BYTES || iterations < targetIterations
    }


    /** Compares two strings without an early exit based on the first differing byte. */
    @JvmStatic
    fun constantTimeEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))

    /** Standard Base64 encoding of UTF-8 text. This is encoding, not encryption. */
    @JvmStatic
    fun base64Encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    /** Decodes standard Base64 as valid UTF-8, or returns null for malformed Base64 or UTF-8. */
    @JvmStatic
    fun base64Decode(value: String): String? {
        if (value.length > MAX_BASE64_ENCODED_CHARS) return null
        return runCatching {
            val bytes = Base64.getDecoder().decode(value)
            try {
                if (bytes.size > MAX_BASE64_DECODE_BYTES) return@runCatching null
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } finally {
                bytes.fill(0)
            }
        }.getOrNull()
    }

    /** Generates cryptographically secure random bytes encoded as URL-safe Base64. */
    @JvmStatic
    fun randomToken(byteCount: Int = 32): String {
        require(byteCount in 16..256) { "Token byte count must be between 16 and 256" }
        val bytes = ByteArray(byteCount).also(random::nextBytes)
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
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

    private fun String.hexToBytesOrNull(expectedLength: Int = 64): ByteArray? {
        if (length != expectedLength || length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }

    private fun decodeBase64(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()
}

/** Concise top-level aliases for common KSL script use. */
fun String.sha256(): String = KSLSecurity.sha256(this)
fun String.sha512(): String = KSLSecurity.sha512(this)
fun File.sha256(): String = KSLSecurity.sha256File(this)
fun File.sha512(): String = KSLSecurity.sha512File(this)
fun String.sha256Matches(expectedHex: String): Boolean = KSLSecurity.verifySha256(this, expectedHex)
fun String.sha512Matches(expectedHex: String): Boolean = KSLSecurity.verifySha512(this, expectedHex)
fun hashPassword(password: String): String = KSLSecurity.hashPassword(password)
fun verifyPassword(password: String, encodedHash: String): Boolean =
    KSLSecurity.verifyPassword(password, encodedHash)
fun hmacSha256(message: String, secret: String): String = KSLSecurity.hmacSha256(message, secret)
fun verifyHmacSha256(message: String, secret: String, expectedHex: String): Boolean =
    KSLSecurity.verifyHmacSha256(message, secret, expectedHex)
fun needsPasswordRehash(encodedHash: String, targetIterations: Int = 210_000): Boolean =
    KSLSecurity.needsPasswordRehash(encodedHash, targetIterations)
fun secureToken(byteCount: Int = 32): String = KSLSecurity.randomToken(byteCount)
fun base64Encode(value: String): String = KSLSecurity.base64Encode(value)
fun base64Decode(value: String): String? = KSLSecurity.base64Decode(value)
