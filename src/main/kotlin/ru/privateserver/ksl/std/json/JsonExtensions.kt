@file:Suppress("unused")

package ru.privateserver.ksl.std.json

import kotlinx.serialization.json.Json
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
import java.math.BigDecimal
import java.math.BigInteger
import java.time.temporal.TemporalAccessor
import java.util.IdentityHashMap
import java.util.UUID

private val jsonNumberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

/** Bounds object-to-JSON conversions so accidental cycles or enormous sequences fail predictably. */
data class KSLJsonLimits(
    val maxDepth: Int = 64,
    val maxNodes: Int = 100_000,
    val maxCollectionItems: Int = 25_000,
    val maxStringLength: Int = 1_048_576
) {
    init {
        require(maxDepth in 1..256) { "maxDepth must be between 1 and 256" }
        require(maxNodes in 1..1_000_000) { "maxNodes must be between 1 and 1000000" }
        require(maxCollectionItems in 1..1_000_000) {
            "maxCollectionItems must be between 1 and 1000000"
        }
        require(maxStringLength in 1..16_777_216) {
            "maxStringLength must be between 1 and 16777216"
        }
    }

    companion object {
        @JvmField
        val DEFAULT = KSLJsonLimits()
    }
}

/** Converts common Kotlin values (including nested maps and collections) into JSON values. */
fun Any?.toJsonElement(): JsonElement = toJsonElement(KSLJsonLimits.DEFAULT)

/**
 * Converts Kotlin values to JSON with explicit depth, node, collection and string bounds.
 * Cyclic maps/collections and infinite or oversized sequences are rejected with an
 * [IllegalArgumentException] instead of overflowing the stack or exhausting memory.
 */
fun Any?.toJsonElement(limits: KSLJsonLimits): JsonElement {
    var visitedNodes = 0
    val activeContainers = IdentityHashMap<Any, Boolean>()

    fun <T> inContainer(container: Any, block: () -> T): T {
        require(activeContainers.put(container, true) == null) {
            "Cyclic object graph cannot be converted to JSON"
        }
        return try {
            block()
        } finally {
            activeContainers.remove(container)
        }
    }

    fun convert(value: Any?, depth: Int): JsonElement {
        require(depth <= limits.maxDepth) {
            "JSON conversion exceeded maximum nesting depth ${limits.maxDepth}"
        }
        visitedNodes++
        require(visitedNodes <= limits.maxNodes) {
            "JSON conversion exceeded maximum node count ${limits.maxNodes}"
        }

        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> {
                require(value.length <= limits.maxStringLength) {
                    "JSON string exceeds maximum length ${limits.maxStringLength}"
                }
                JsonPrimitive(value)
            }
            is Char -> JsonPrimitive(value.toString())
            is Boolean -> JsonPrimitive(value)
            is Byte, is Short, is Int, is Long -> JsonPrimitive(value as Number)
            is BigInteger, is BigDecimal -> {
                val number = value.toString()
                require(jsonNumberPattern.matches(number)) {
                    "Number is not a valid finite JSON number: $number"
                }
                JsonPrimitive(value as Number)
            }
            is Float -> {
                require(value.isFinite()) { "JSON does not support non-finite numbers" }
                JsonPrimitive(value)
            }
            is Double -> {
                require(value.isFinite()) { "JSON does not support non-finite numbers" }
                JsonPrimitive(value)
            }
            is Number -> {
                val number = value.toString()
                require(jsonNumberPattern.matches(number)) {
                    "Number is not a valid finite JSON number: $number"
                }
                JsonPrimitive(value)
            }
            is Enum<*> -> JsonPrimitive(value.name)
            is TemporalAccessor -> JsonPrimitive(value.toString())
            is UUID -> JsonPrimitive(value.toString())
            is Map<*, *> -> inContainer(value) {
                buildJsonObject {
                    var count = 0
                    value.forEach { (key, child) ->
                        count++
                        require(count <= limits.maxCollectionItems) {
                            "JSON object exceeds maximum entry count ${limits.maxCollectionItems}"
                        }
                        require(key is String) {
                            "JSON object keys must be strings, got ${key?.let { it::class.simpleName } ?: "null"}"
                        }
                        require(key.length <= limits.maxStringLength) {
                            "JSON object key exceeds maximum length ${limits.maxStringLength}"
                        }
                        put(key, convert(child, depth + 1))
                    }
                }
            }
            is Iterable<*> -> inContainer(value) {
                buildJsonArray {
                    var count = 0
                    value.forEach { child ->
                        count++
                        require(count <= limits.maxCollectionItems) {
                            "JSON array exceeds maximum item count ${limits.maxCollectionItems}"
                        }
                        add(convert(child, depth + 1))
                    }
                }
            }
            is Sequence<*> -> inContainer(value) {
                buildJsonArray {
                    val iterator = value.iterator()
                    var count = 0
                    while (iterator.hasNext()) {
                        require(count < limits.maxCollectionItems) {
                            "JSON sequence exceeds maximum item count ${limits.maxCollectionItems}"
                        }
                        add(convert(iterator.next(), depth + 1))
                        count++
                    }
                }
            }
            is Array<*> -> inContainer(value) {
                require(value.size <= limits.maxCollectionItems) {
                    "JSON array exceeds maximum item count ${limits.maxCollectionItems}"
                }
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is ByteArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is ShortArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is IntArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is LongArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is FloatArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is DoubleArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is BooleanArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it, depth + 1)) } }
            }
            is CharArray -> {
                require(value.size <= limits.maxCollectionItems)
                buildJsonArray { value.forEach { add(convert(it.toString(), depth + 1)) } }
            }
            else -> throw IllegalArgumentException(
                "Unsupported JSON value type: ${value::class.qualifiedName}"
            )
        }
    }

    return convert(this, 0)
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

private val strictJsonCodec = Json {
    explicitNulls = true
    allowSpecialFloatingPointValues = false
}

private val compatibleJsonCodec = Json {
    explicitNulls = true
    allowSpecialFloatingPointValues = false
    allowComments = true
    allowTrailingComma = true
}

/** Parses strict RFC 8259 JSON and wraps syntax errors with a concise script-friendly message. */
fun parseJson(source: String): JsonElement = parseWithCodec(source, strictJsonCodec)

/**
 * Parses JSON plus common configuration-file extensions: // and /* */ comments,
 * and trailing commas. Serialization remains standard JSON and omits comments.
 */
fun parseJsonCompatible(source: String): JsonElement = parseWithCodec(source, compatibleJsonCodec)

private fun parseWithCodec(source: String, codec: Json): JsonElement {
    require(source.isNotBlank()) { "JSON source must not be blank" }
    return try {
        codec.parseToJsonElement(source.removePrefix("\uFEFF"))
    } catch (ex: Exception) {
        throw IllegalArgumentException("Invalid JSON: ${ex.message ?: ex::class.simpleName}", ex)
    }
}

/** Parses strict JSON without throwing; malformed input returns null. JSON literal null returns JsonNull. */
fun parseJsonOrNull(source: String): JsonElement? =
    runCatching { parseJson(source) }.getOrNull()

/** Parses compatible JSON without throwing; malformed input returns null. */
fun parseJsonCompatibleOrNull(source: String): JsonElement? =
    runCatching { parseJsonCompatible(source) }.getOrNull()

/** Serializes a JSON value as compact or human-readable standards-compliant JSON. */
fun JsonElement.toJsonString(pretty: Boolean = false): String =
    Json { prettyPrint = pretty; explicitNulls = true; allowSpecialFloatingPointValues = false }
        .encodeToString(JsonElement.serializer(), this)

/**
 * Converts a JSON tree into ordinary mutable Kotlin values.
 * Integers retain 64-bit precision where possible and fall back to BigInteger;
 * decimal/exponent values become BigDecimal for loss-minimizing TOML/JSON transfer.
 */
fun JsonElement.toKotlinValue(limits: KSLJsonLimits = KSLJsonLimits.DEFAULT): Any? {
    var visitedNodes = 0

    fun convert(value: JsonElement, depth: Int): Any? {
        require(depth <= limits.maxDepth) {
            "JSON conversion exceeded maximum nesting depth ${limits.maxDepth}"
        }
        visitedNodes++
        require(visitedNodes <= limits.maxNodes) {
            "JSON conversion exceeded maximum node count ${limits.maxNodes}"
        }

        return when (value) {
            JsonNull -> null
            is JsonObject -> {
                require(value.size <= limits.maxCollectionItems) {
                    "JSON object exceeds maximum entry count ${limits.maxCollectionItems}"
                }
                linkedMapOf<String, Any?>().apply {
                    value.forEach { (key, child) ->
                        require(key.length <= limits.maxStringLength) {
                            "JSON object key exceeds maximum length ${limits.maxStringLength}"
                        }
                        put(key, convert(child, depth + 1))
                    }
                }
            }
            is JsonArray -> {
                require(value.size <= limits.maxCollectionItems) {
                    "JSON array exceeds maximum item count ${limits.maxCollectionItems}"
                }
                value.map { convert(it, depth + 1) }
            }
            is JsonPrimitive -> when {
                value.isString -> {
                    require(value.content.length <= limits.maxStringLength) {
                        "JSON string exceeds maximum length ${limits.maxStringLength}"
                    }
                    value.content
                }
                value.booleanOrNull != null -> value.booleanOrNull
                else -> parseKotlinNumber(value.content)
            }
        }
    }

    return convert(this, 0)
}

private fun parseKotlinNumber(content: String): Number {
    require(jsonNumberPattern.matches(content)) { "Invalid JSON number: $content" }
    if (!content.contains('.') && !content.contains('e', ignoreCase = true)) {
        return content.toLongOrNull() ?: BigInteger(content)
    }
    return BigDecimal(content)
}

/**
 * Resolves a dot-separated path through objects and arrays.
 * Numeric path segments address array indices: "profile.roles.0.name".
 */
fun JsonElement.atPath(path: String): JsonElement? {
    if (path.isBlank()) return null
    return path.split('.').fold(this as JsonElement?) { current, segment ->
        when (val value = current) {
            is JsonObject -> value[segment]
            is JsonArray -> segment.toIntOrNull()?.let(value::getOrNull)
            else -> null
        }
    }
}

/** Resolves a path or throws an error that identifies the missing JSON field. */
fun JsonElement.requireAtPath(path: String): JsonElement =
    atPath(path) ?: throw IllegalArgumentException("Required JSON path '$path' was not found")

/** Reads an RFC 6901 JSON Pointer, including keys containing dots or slashes. */
fun JsonElement.atJsonPointer(pointer: String): JsonElement? {
    if (pointer.isEmpty()) return this
    if (!pointer.startsWith('/')) return null
    return pointer.drop(1).split('/').fold(this as JsonElement?) { current, rawSegment ->
        val segment = rawSegment.replace("~1", "/").replace("~0", "~")
        when (val value = current) {
            is JsonObject -> value[segment]
            is JsonArray -> segment.toIntOrNull()?.let(value::getOrNull)
            else -> null
        }
    }
}

/** Deep-merges objects; nested objects merge recursively while arrays and scalar values are replaced. */
fun JsonObject.deepMerge(override: JsonObject): JsonObject = buildJsonObject {
    this@deepMerge.forEach { (key, value) ->
        val replacement = override[key]
        put(
            key,
            if (value is JsonObject && replacement is JsonObject) value.deepMerge(replacement)
            else replacement ?: value
        )
    }
    override.forEach { (key, value) -> if (!containsKey(key)) put(key, value) }
}

/** Typed accessors for a nested path. They do not coerce strings, numbers or booleans. */
fun JsonElement.stringAtPath(path: String): String? = atPath(path)?.stringOrNull()
fun JsonElement.intAtPath(path: String): Int? = atPath(path)?.intOrNull()
fun JsonElement.longAtPath(path: String): Long? = atPath(path)?.longOrNull()
fun JsonElement.doubleAtPath(path: String): Double? = atPath(path)?.doubleOrNull()?.takeIf { it.isFinite() }
fun JsonElement.booleanAtPath(path: String): Boolean? = atPath(path)?.booleanOrNull()
