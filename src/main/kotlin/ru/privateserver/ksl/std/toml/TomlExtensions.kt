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

/**
 * TOML 1.0-compatible codec for scalar values, nested tables, inline tables,
 * arrays and arrays of tables.
 *
 * TOML has no null value. Nulls and unsupported Kotlin/JVM types are rejected
 * instead of silently converting them into strings or dropping data.
 */
object KSLTomlCodec {
    private val integerMin = BigInteger.valueOf(Long.MIN_VALUE)
    private val integerMax = BigInteger.valueOf(Long.MAX_VALUE)

    @JvmStatic
    fun parse(source: String): Map<String, Any?> {
        val parsed = Toml.parse(source.removePrefix("\uFEFF"))
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

    private fun appendTable(
        out: StringBuilder,
        table: Map<*, *>,
        path: List<String>,
        arrayTable: Boolean = false
    ) {
        if (path.isNotEmpty()) {
            if (out.isNotEmpty()) out.append('\n')
            val header = path.joinToString(".") { key(it) }
            if (arrayTable) out.append("[[").append(header).append("]]\n")
            else out.append('[').append(header).append("]\n")
        }

        // TOML requires a table's scalar values to appear before child table headers.
        table.forEach { (rawName, item) ->
            val name = requireKey(rawName)
            if (item !is Map<*, *> && !isArrayOfTables(item)) {
                out.append(key(name)).append(" = ").append(renderValue(item)).append('\n')
            }
        }

        table.forEach { (rawName, item) ->
            if (item is Map<*, *>) {
                val name = requireKey(rawName)
                appendTable(out, item, path + name)
            }
        }

        table.forEach { (rawName, item) ->
            if (isArrayOfTables(item)) {
                val name = requireKey(rawName)
                (item as List<*>).forEach { entry ->
                    appendTable(out, entry as Map<*, *>, path + name, arrayTable = true)
                }
            }
        }
    }

    private fun isArrayOfTables(value: Any?): Boolean =
        value is List<*> && value.isNotEmpty() && value.all { it is Map<*, *> }

    private fun requireKey(value: Any?): String =
        value as? String ?: throw IllegalArgumentException(
            "TOML table keys must be strings, got ${value?.let { it::class.simpleName } ?: "null"}"
        )

    private fun key(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_-]+"))) value else quote(value)

    private fun renderValue(item: Any?): String = when (item) {
        is String -> quote(item)
        is Char -> quote(item.toString())
        is Boolean -> item.toString()
        is Byte, is Short, is Int, is Long -> item.toString()
        is BigInteger -> {
            require(item >= integerMin && item <= integerMax) {
                "TOML integers must fit into a signed 64-bit value"
            }
            item.toString()
        }
        is BigDecimal -> {
            val rendered = item.toString()
            if (rendered.contains('.') || rendered.contains('e', ignoreCase = true)) rendered else "$rendered.0"
        }
        is Float -> {
            require(item.isFinite()) { "TOML does not support non-finite numbers" }
            item.toString()
        }
        is Double -> {
            require(item.isFinite()) { "TOML does not support non-finite numbers" }
            item.toString()
        }
        is LocalDate, is LocalTime, is LocalDateTime, is OffsetDateTime -> item.toString()
        is List<*> -> item.joinToString(prefix = "[", postfix = "]") { value ->
            require(value != null) { "TOML does not support null values" }
            renderValue(value)
        }
        is Map<*, *> -> renderInlineTable(item)
        null -> error("TOML does not support null values")
        else -> error("Unsupported TOML value type: " + item.javaClass.name)
    }

    private fun renderInlineTable(table: Map<*, *>): String {
        val values = table.entries.joinToString(", ") { (rawName, value) ->
            val name = requireKey(rawName)
            "${key(name)} = ${renderValue(value)}"
        }
        return if (values.isEmpty()) "{ }" else "{ $values }"
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
                else -> if (char.code < 0x20 || char.code in 0x7f..0x9f) {
                    append("\\u").append(char.code.toString(16).uppercase().padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }
}

/** Typed, default-friendly accessors for values returned by [KSLTomlCodec.parse]. */
fun Map<String, Any?>.tomlHas(key: String): Boolean = containsKey(key)
fun Map<String, Any?>.tomlString(key: String, default: String = ""): String = this[key] as? String ?: default
fun Map<String, Any?>.tomlLong(key: String, default: Long = 0L): Long =
    (this[key] as? Number)?.exactLongOrNull() ?: default
fun Map<String, Any?>.tomlInt(key: String, default: Int = 0): Int =
    (this[key] as? Number)?.exactIntOrNull() ?: default
fun Map<String, Any?>.tomlDouble(key: String, default: Double = 0.0): Double =
    (this[key] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: default

private fun Number.exactLongOrNull(): Long? = when (this) {
    is Byte, is Short, is Int, is Long -> toLong()
    is BigInteger -> runCatching { longValueExact() }.getOrNull()
    is BigDecimal -> runCatching { longValueExact() }.getOrNull()
    else -> null
}

private fun Number.exactIntOrNull(): Int? = when (this) {
    is Byte, is Short, is Int -> toInt()
    is Long -> takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    is BigInteger -> runCatching { intValueExact() }.getOrNull()
    is BigDecimal -> runCatching { intValueExact() }.getOrNull()
    else -> null
}

fun Map<String, Any?>.tomlBoolean(key: String, default: Boolean = false): Boolean =
    this[key] as? Boolean ?: default

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.tomlTable(key: String): Map<String, Any?>? =
    this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.tomlList(key: String): List<Any?> =
    this[key] as? List<Any?> ?: emptyList()

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
