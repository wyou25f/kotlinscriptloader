package ru.privateserver.ksl.std.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonExtensionsTest {
    private enum class State { READY }

    @Test
    fun conversionHandlesPrimitivesEnumsAndNestedCollections() {
        val value = mapOf(
            "text" to 'K',
            "state" to State.READY,
            "numbers" to arrayOf(1, 2L, 3.5),
            "nested" to listOf(true, null)
        ).toJsonElement() as JsonObject

        assertEquals("K", value.string("text"))
        assertEquals("READY", value.string("state"))
        assertEquals(3, (value["numbers"] as JsonArray).size)
        assertEquals(2, (value["nested"] as JsonArray).size)
    }

    @Test
    fun buildersPreserveNestedValues() {
        val obj = jsonObjectOf("enabled" to true, "items" to listOf("a", "b"))
        val array = jsonArrayOf(1, "two", null)

        assertEquals(true, obj.bool("enabled"))
        assertEquals(2, obj.arrayValue("items")?.size)
        assertEquals(3, array.size)
        assertEquals(JsonPrimitive("two"), array[1])
    }

    @Test
    fun typedAccessorsUseDefaultsForMissingOrIncompatibleValues() {
        val value = jsonObjectOf("text" to "hello", "count" to 4, "score" to 2.5, "enabled" to true)

        assertEquals("hello", value.string("text"))
        assertEquals(4, value.int("count"))
        assertEquals(4L, value.long("count"))
        assertEquals(2.5, value.double("score"))
        assertEquals(true, value.bool("enabled"))
        assertEquals("fallback", value.string("missing", "fallback"))
        assertEquals(7, value.int("text", 7))
        assertNull(value.objectValue("text"))
        assertNull(value.arrayValue("text"))
    }

    @Test
    fun stringAccessorsDoNotCoerceNumbersOrBooleans() {
        assertNull(JsonPrimitive(42).stringOrNull())
        assertNull(JsonPrimitive(true).stringOrNull())
        assertEquals("42", JsonPrimitive("42").stringOrNull())
    }

    @Test
    fun primitiveArraysAndSequencesConvertToJsonArrays() {
        assertEquals("[1,2,3]", intArrayOf(1, 2, 3).toJsonElement().toString())
        assertEquals("[true,false]", sequenceOf(true, false).toJsonElement().toString())
        assertEquals("[\"a\",\"b\"]", charArrayOf('a', 'b').toJsonElement().toString())
    }

    @Test
    fun requiredJsonAccessorsAreStrictAndArrayAccessorsAreSafe() {
        val obj = jsonObjectOf("text" to "hello", "count" to 5, "enabled" to true)

        assertEquals(true, obj.hasKey("count"))
        assertEquals("hello", obj.requireString("text"))
        assertEquals(5, obj.requireInt("count"))
        assertEquals(true, obj.requireBoolean("enabled"))
        assertEquals(5, jsonArrayOf(5, "x").intAt(0))
        assertNull(jsonArrayOf(5, "x").intAt(9))
        assertThrows(IllegalStateException::class.java) { obj.requireString("count") }
    }

    @Test
    fun invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Double.NaN.toJsonElement() }
        assertThrows(IllegalArgumentException::class.java) { Float.POSITIVE_INFINITY.toJsonElement() }
        assertThrows(IllegalArgumentException::class.java) { mapOf(1 to "bad").toJsonElement() }
        assertThrows(IllegalArgumentException::class.java) { Any().toJsonElement() }
    }

    @Test
    fun parserAndFormatterSupportCompactAndPrettyJson() {
        val parsed = parseJson("""{"name":"KSL","enabled":true}""")
        assertEquals("""{"name":"KSL","enabled":true}""", parsed.toJsonString())
        assertTrue(parsed.toJsonString(pretty = true).contains("\n"))
        assertEquals(JsonNull, parseJsonOrNull("null"))
        assertNull(parseJsonOrNull("{broken"))
        assertThrows(IllegalArgumentException::class.java) { parseJson("") }
    }

    @Test
    fun compatibleParserAcceptsJsonCommentsTrailingCommasAndBom() {
        val source = "\uFEFF" + """
            {
              // values from older hand-edited config files
              "name": "KSL",
              "items": [1, 2,],
            }
        """.trimIndent()

        val parsed = parseJsonCompatible(source) as JsonObject
        assertEquals("KSL", parsed.string("name"))
        assertEquals(2, parsed.arrayValue("items")?.size)
        assertThrows(IllegalArgumentException::class.java) {
            parseJson("""{"name":"KSL", // comment
                "items":[1,2,]}""")
        }
        assertEquals(null, parseJsonCompatibleOrNull("{broken"))
    }

    @Test
    fun conversionPreservesLargeNumbersAndSupportsTomlTemporalValues() {
        val tree = parseJson(
            """{"large":9223372036854775808,"precise":0.123456789012345678901,"date":"2026-11-17"}"""
        )
        val values = tree.toKotlinValue() as Map<*, *>

        assertTrue(values["large"] is java.math.BigInteger)
        assertEquals(java.math.BigDecimal("0.123456789012345678901"), values["precise"])
        assertEquals(tree, values.toJsonElement())
        assertEquals(JsonPrimitive("2026-11-17"), java.time.LocalDate.parse("2026-11-17").toJsonElement())
    }

    @Test
    fun conversionRejectsCyclesAndEnforcesDepthNodeAndSequenceLimits() {
        val cycle = mutableListOf<Any?>()
        cycle += cycle

        assertThrows(IllegalArgumentException::class.java) { cycle.toJsonElement() }
        assertThrows(IllegalArgumentException::class.java) {
            listOf(listOf("deep")).toJsonElement(KSLJsonLimits(maxDepth = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            listOf("one", "two").toJsonElement(KSLJsonLimits(maxNodes = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sequenceOf(1, 2, 3).toJsonElement(KSLJsonLimits(maxCollectionItems = 2))
        }
    }

    @Test
    fun jsonPointerResolvesKeysWithDotsSlashesAndTildes() {
        val tree = parseJson(
            """{"a.b":{"x/y":"value"},"tilde~key":[{"ok":true}]}"""
        )

        assertEquals("value", tree.atJsonPointer("/a.b/x~1y")?.stringOrNull())
        assertEquals(true, tree.atJsonPointer("/tilde~0key/0/ok")?.booleanOrNull())
        assertNull(tree.atJsonPointer("not/a/pointer"))
    }

    @Test
    fun nestedPathsResolveObjectKeysAndArrayIndices() {
        val value = parseJson("""{"profile":{"roles":[{"name":"admin"}]}}""")
        assertEquals("admin", value.stringAtPath("profile.roles.0.name"))
        assertEquals("admin", value.requireAtPath("profile.roles.0.name").stringOrNull())
        assertNull(value.atPath("profile.roles.4.name"))
        assertNull(value.atPath("profile.roles.not-an-index.name"))
        assertThrows(IllegalArgumentException::class.java) { value.requireAtPath("missing.path") }
    }

    @Test
    fun deepMergeRecursivelyOverridesObjectsAndReplacesArrays() {
        val base = parseJson("""{"profile":{"name":"R","rank":"member"},"items":[1,2],"enabled":true}""") as JsonObject
        val override = parseJson("""{"profile":{"rank":"admin"},"items":[3],"new":"value"}""") as JsonObject
        val merged = base.deepMerge(override)
        assertEquals("R", merged.objectValue("profile")?.string("name"))
        assertEquals("admin", merged.objectValue("profile")?.string("rank"))
        assertEquals("[3]", merged.arrayValue("items").toString())
        assertEquals("value", merged.string("new"))
        assertEquals(true, merged.bool("enabled"))
    }
}
