@file:Suppress("unused")

package ru.privateserver.ksl.std.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private val jsonNumberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/** Converts common Kotlin values (including nested maps and collections) into JSON values. */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Char -> JsonPrimitive(this.toString())
    is Boolean -> JsonPrimitive(this)
    is Byte, is Short, is Int, is Long -> JsonPrimitive(this as Number)
    is Float -> {
        require(isFinite()) { "JSON does not support non-finite numbers" }
        JsonPrimitive(this)
    }
    is Double -> {
        require(isFinite()) { "JSON does not support non-finite numbers" }
        JsonPrimitive(this)
    }
    is Number -> {
        require(jsonNumberPattern.matches(toString())) { "Number is not a valid finite JSON number: " + toString() }
        JsonPrimitive(this)
    }
    is Enum<*> -> JsonPrimitive(name)
    is Map<*, *> -> buildJsonObject {
        this@toJsonElement.forEach { (key, value) ->
            require(key is String) { "JSON object keys must be strings, got ${key?.let { it::class.simpleName } ?: "null"}" }
            put(key, value.toJsonElement())
        }
    }
    is Iterable<*> -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    is Array<*> -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    is ByteArray -> buildJsonArray { this@toJsonElement.forEach { add(it) } }
    is ShortArray -> buildJsonArray { this@toJsonElement.forEach { add(it) } }
    is IntArray -> buildJsonArray { this@toJsonElement.forEach { add(it) } }
    is LongArray -> buildJsonArray { this@toJsonElement.forEach { add(it) } }
    is FloatArray -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    is DoubleArray -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    is BooleanArray -> buildJsonArray { this@toJsonElement.forEach { add(it) } }
    is CharArray -> buildJsonArray { this@toJsonElement.forEach { add(it.toString()) } }
    is Sequence<*> -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    else -> throw IllegalArgumentException("Unsupported JSON value type: ${this::class.qualifiedName}")
}

/** Builds a JSON object from ordinary Kotlin values. */
fun jsonObjectOf(vararg entries: Pair<String, Any?>): JsonObject = buildJsonObject {
    entries.forEach { (key, value) -> put(key, value.toJsonElement()) }
}

/** Builds a JSON array from ordinary Kotlin values. */
fun jsonArrayOf(vararg values: Any?): JsonArray = buildJsonArray {
    values.forEach { add(it.toJsonElement()) }
}

/** Returns text only for JSON string primitives; numbers and booleans are not coerced. */
fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
fun JsonElement.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull
fun JsonElement.longOrNull(): Long? = (this as? JsonPrimitive)?.longOrNull
fun JsonElement.doubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
fun JsonElement.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

/** Typed, default-friendly accessors for common JSON object fields. */
fun JsonObject.string(key: String, default: String = ""): String = this[key]?.stringOrNull() ?: default
fun JsonObject.int(key: String, default: Int = 0): Int = this[key]?.intOrNull() ?: default
fun JsonObject.long(key: String, default: Long = 0L): Long = this[key]?.longOrNull() ?: default
fun JsonObject.double(key: String, default: Double = 0.0): Double = this[key]?.doubleOrNull() ?: default
fun JsonObject.bool(key: String, default: Boolean = false): Boolean = this[key]?.booleanOrNull() ?: default
fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.hasKey(key: String): Boolean = containsKey(key)

fun JsonObject.requireString(key: String): String =
    this[key]?.stringOrNull() ?: error("JSON field '$key' must be a string")
fun JsonObject.requireInt(key: String): Int =
    this[key]?.intOrNull() ?: error("JSON field '$key' must be a 32-bit integer")
fun JsonObject.requireLong(key: String): Long =
    this[key]?.longOrNull() ?: error("JSON field '$key' must be a 64-bit integer")
fun JsonObject.requireDouble(key: String): Double =
    this[key]?.doubleOrNull()?.takeIf { it.isFinite() } ?: error("JSON field '$key' must be a finite number")
fun JsonObject.requireBoolean(key: String): Boolean =
    this[key]?.booleanOrNull() ?: error("JSON field '$key' must be a boolean")

fun JsonArray.stringAt(index: Int): String? = getOrNull(index)?.stringOrNull()
fun JsonArray.intAt(index: Int): Int? = getOrNull(index)?.intOrNull()
fun JsonArray.longAt(index: Int): Long? = getOrNull(index)?.longOrNull()
fun JsonArray.doubleAt(index: Int): Double? = getOrNull(index)?.doubleOrNull()?.takeIf { it.isFinite() }
fun JsonArray.boolAt(index: Int): Boolean? = getOrNull(index)?.booleanOrNull()
