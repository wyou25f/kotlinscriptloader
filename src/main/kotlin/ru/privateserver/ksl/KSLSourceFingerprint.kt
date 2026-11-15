package ru.privateserver.ksl

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable SHA-256 identity for KSL script source snapshots. */
internal object KSLSourceFingerprint {
    fun of(source: String): String = of(source.toByteArray(StandardCharsets.UTF_8))
    fun of(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}