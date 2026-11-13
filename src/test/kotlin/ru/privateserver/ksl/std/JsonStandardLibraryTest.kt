package ru.privateserver.ksl.std

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.std.json.arrayValue
import ru.privateserver.ksl.std.json.bool
import ru.privateserver.ksl.std.json.doubleOrNull
import ru.privateserver.ksl.std.json.int
import ru.privateserver.ksl.std.json.intOrNull
import ru.privateserver.ksl.std.json.jsonArrayOf
import ru.privateserver.ksl.std.json.jsonObjectOf
import ru.privateserver.ksl.std.json.long
import ru.privateserver.ksl.std.json.objectValue
import ru.privateserver.ksl.std.json.string
import ru.privateserver.ksl.std.json.stringOrNull
import ru.privateserver.ksl.std.json.toJsonElement

class JsonStandardLibraryTest {
    @Test
    fun `converts nested Kotlin values to JSON`() {
        val value = jsonObjectOf(
            "name" to "Alex",
            "coins" to 125,
            "active" to true,
            "profile" to mapOf("rank" to "gold"),
            "tags" to listOf("builder", "vip"),
            "nothing" to null
        )
        assertEquals("Alex", value.string("name"))
        assertEquals(125, value.int("coins"))
        assertTrue(value.bool("active"))
        assertEquals("gold", value.objectValue("profile")?.string("rank"))
        assertEquals(2, value.arrayValue("tags")?.size)
        assertEquals(JsonPrimitive("Alex"), value["name"])
    }

    @Test
    fun `array helper accepts primitives and nested values`() {
        val array = jsonArrayOf("hello", 42, mapOf("enabled" to true), null)
        assertEquals(4, array.size)
        assertEquals("hello", array[0].stringOrNull())
        assertEquals(42, array[1].intOrNull())
        assertEquals(true, (array[2] as JsonObject).bool("enabled"))
        assertEquals("null", array[3].toString())
    }

    @Test
    fun `typed accessors return defaults for missing or mismatched fields`() {
        val value = jsonObjectOf("count" to "not-a-number", "score" to 3.5)
        assertEquals(7, value.int("count", 7))
        assertEquals(0L, value.long("missing"))
        assertEquals(3.5, value["score"]?.doubleOrNull())
        assertEquals("", value.string("missing"))
        assertNull(value.objectValue("count"))
    }

    @Test
    fun `conversion rejects unsupported and non finite values`() {
        assertThrows(IllegalArgumentException::class.java) { Any().toJsonElement() }
        assertThrows(IllegalArgumentException::class.java) { Double.NaN.toJsonElement() }
        assertThrows(IllegalArgumentException::class.java) { mapOf(1 to "invalid").toJsonElement() }
    }

    @Test
    fun `generated values round trip through kotlinx json parser`() {
        val original = jsonObjectOf("list" to listOf(1, 2, 3), "enabled" to false)
        val parsed = Json.parseToJsonElement(original.toString())
        assertEquals(original, parsed)
        assertTrue(parsed is JsonObject)
        assertTrue((parsed as JsonObject)["list"] is JsonArray)
    }
}
