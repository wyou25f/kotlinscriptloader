@file:Suppress("unused")

package ru.privateserver.ksl.std.toml

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/** TOML codec: scalar values, arrays and nested tables; arrays of tables are rejected. */
object KSLTomlCodec {
    @JvmStatic
    fun parse(source: String): Map<String, Any?> {
        val parsed = Toml.parse(source)
        require(parsed.errors().isEmpty()) {
            parsed.errors().joinToString("; ") { it.toString() }
        }
        return mutableCopy(parsed.toMap())
    }

    @JvmStatic
    fun encode(values: Map<String, Any?>): String = buildString {
        appendTable(this, values, emptyList())
    }

    @JvmStatic
    fun mutableCopy(source: Map<*, *>): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            source.forEach { (key, value) ->
                require(key is String) { "TOML table keys must be strings" }
                put(key, mutableValue(value))
            }
        }

    private fun mutableValue(value: Any?): Any? = when (value) {
        is TomlTable -> mutableCopy(value.toMap())
        is TomlArray -> value.toList().map(::mutableValue)
        is Map<*, *> -> mutableCopy(value)
        is List<*> -> value.map(::mutableValue)
        else -> value
    }

    private fun appendTable(out: StringBuilder, table: Map<String, Any?>, path: List<String>) {
        if (path.isNotEmpty()) {
            out.append('[').append(path.joinToString(".") { key(it) }).append("]\n")
        }
        table.forEach { (name, item) ->
            if (item !is Map<*, *>) {
                out.append(key(name)).append(" = ").append(renderValue(item)).append('\n')
            }
        }
        table.forEach { (name, item) ->
            if (item is Map<*, *>) {
                if (out.isNotEmpty()) out.append('\n')
                @Suppress("UNCHECKED_CAST")
                appendTable(out, item as Map<String, Any?>, path + name)
            }
        }
    }

    private fun key(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_-]+"))) value else quote(value)

    private fun renderValue(item: Any?): String = when (item) {
        is String -> quote(item)
        is Char -> quote(item.toString())
        is Boolean -> item.toString()
        is Byte, is Short, is Int, is Long, is BigInteger, is BigDecimal -> item.toString()
        is Float -> {
            require(item.isFinite()) { "TOML does not support non-finite numbers" }
            item.toString()
        }
        is Double -> {
            require(item.isFinite()) { "TOML does not support non-finite numbers" }
            item.toString()
        }
        is LocalDate, is LocalTime, is LocalDateTime, is OffsetDateTime -> item.toString()
        is List<*> -> item.joinToString(prefix = "[", postfix = "]") {
            require(it != null && it !is Map<*, *>) {
                "TOML arrays cannot contain null values or inline tables in this writer"
            }
            renderValue(it)
        }
        is Map<*, *> -> error("Nested TOML tables must be emitted as tables")
        null -> error("TOML does not support null values")
        else -> error("Unsupported TOML value type: " + item.javaClass.name)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (char.code < 0x20 || char.code == 0x7f) {
                    append("\\u%04X".format(char.code))
                } else append(char)
            }
        }
        append('"')
    }
}

/** Typed, default-friendly accessors for values returned by [KSLTomlCodec.parse]. */
fun Map<String, Any?>.tomlHas(key: String): Boolean = containsKey(key)
fun Map<String, Any?>.tomlString(key: String, default: String = ""): String = this[key] as? String ?: default
fun Map<String, Any?>.tomlLong(key: String, default: Long = 0L): Long = (this[key] as? Number)?.toLong() ?: default
fun Map<String, Any?>.tomlInt(key: String, default: Int = 0): Int = (this[key] as? Number)?.toInt() ?: default
fun Map<String, Any?>.tomlDouble(key: String, default: Double = 0.0): Double = (this[key] as? Number)?.toDouble() ?: default
fun Map<String, Any?>.tomlBoolean(key: String, default: Boolean = false): Boolean = this[key] as? Boolean ?: default
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.tomlTable(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.tomlList(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()
fun MutableMap<String, Any?>.putTomlIfAbsent(key: String, value: Any?): Boolean {
    if (containsKey(key)) return false
    this[key] = value
    return true
}
fun MutableMap<String, Any?>.removeToml(key: String): Any? = remove(key)

/** Concise aliases for TOML operations exposed by the KSL script context. */
fun ru.privateserver.ksl.BukkitScriptContext.readToml(fileName: String): Map<String, Any?>? =
    loadToml(fileName)
fun ru.privateserver.ksl.BukkitScriptContext.writeToml(fileName: String, values: Map<String, Any?>): Boolean =
    saveToml(fileName, values)
fun ru.privateserver.ksl.BukkitScriptContext.updateToml(
    fileName: String,
    block: MutableMap<String, Any?>.() -> Unit
): Map<String, Any?> = editToml(fileName, block)
