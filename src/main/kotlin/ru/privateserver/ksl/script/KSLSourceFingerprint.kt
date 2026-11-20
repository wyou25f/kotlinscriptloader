package ru.privateserver.ksl.script

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable SHA-256 identity for a KSL script source snapshot. */
internal object KSLSourceFingerprint {
    private const val HEX = "0123456789abcdef"

    fun of(source: String): String = of(source.toByteArray(StandardCharsets.UTF_8))

    fun of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val encoded = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            encoded[index * 2] = HEX[value ushr 4]
            encoded[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(encoded)
    }
}
