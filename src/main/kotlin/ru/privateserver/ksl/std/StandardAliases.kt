@file:Suppress("unused")

package ru.privateserver.ksl.std

import java.io.File
import ru.privateserver.ksl.std.json.jsonArrayOf
import ru.privateserver.ksl.std.json.jsonObjectOf
import ru.privateserver.ksl.std.json.toJsonElement
import ru.privateserver.ksl.std.security.KSLSecurity

/** Compact aliases for frequently used Standard Library operations. */
fun String.sha256sum(): String = KSLSecurity.sha256(this)
fun File.sha256sum(): String = KSLSecurity.sha256File(this)
fun String.passwordMatches(encodedHash: String): Boolean = KSLSecurity.verifyPassword(this, encodedHash)
fun passwordHash(password: String): String = KSLSecurity.hashPassword(password)
fun passwordCheck(password: String, encodedHash: String): Boolean =
    KSLSecurity.verifyPassword(password, encodedHash)
fun token(bytes: Int = 32): String = KSLSecurity.randomToken(bytes)
fun jsonObj(vararg entries: Pair<String, Any?>) = jsonObjectOf(*entries)
fun jsonArr(vararg values: Any?) = jsonArrayOf(*values)
fun Any?.json() = toJsonElement()
