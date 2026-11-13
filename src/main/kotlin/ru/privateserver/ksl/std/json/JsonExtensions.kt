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
    is Number -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(name)
    is Map<*, *> -> buildJsonObject {
        this@toJsonElement.forEach { (key, value) ->
            require(key is String) { "JSON object keys must be strings, got ${key?.let { it::class.simpleName } ?: "null"}" }
            put(key, value.toJsonElement())
        }
    }
    is Iterable<*> -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    is Array<*> -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
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

fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
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
