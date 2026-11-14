package ru.privateserver.ksl.std.toml

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

class KSLTomlCodecTest {
    @Test
    fun encodesAndParsesNestedTablesAndArrays() {
        val input = mapOf(
            "name" to "KSL",
            "enabled" to true,
            "count" to 12,
            "tags" to listOf("paper", "kotlin"),
            "database" to mapOf("host" to "localhost", "port" to 3306)
        )
        val parsed = KSLTomlCodec.parse(KSLTomlCodec.encode(input))
        assertEquals("KSL", parsed["name"])
        assertEquals(true, parsed["enabled"])
        assertEquals(12L, parsed["count"])
        assertEquals(listOf("paper", "kotlin"), parsed["tags"])
        val database = parsed["database"] as Map<*, *>
        assertEquals("localhost", database["host"])
        assertEquals(3306L, database["port"])
    }

    @Test
    fun escapesQuotedKeysAndStrings() {
        val parsed = KSLTomlCodec.parse(KSLTomlCodec.encode(mapOf("display name" to "line1\nline2")))
        assertEquals("line1\nline2", parsed["display name"])
    }

    @Test
    fun typedAccessorsAndMutableAliasesHandleParsedValues() {
        val parsed = KSLTomlCodec.parse(
            "name = \"KSL\"\nport = 25565\nenabled = true\nratio = 1.5\ntags = [\"a\", \"b\"]\n"
        )
        assertTrue(parsed.tomlHas("name"))
        assertEquals("KSL", parsed.tomlString("name"))
        assertEquals(25565, parsed.tomlInt("port"))
        assertEquals(25565L, parsed.tomlLong("port"))
        assertTrue(parsed.tomlBoolean("enabled"))
        assertEquals(1.5, parsed.tomlDouble("ratio"), 0.0001)
        assertEquals(listOf("a", "b"), parsed.tomlList("tags"))
        assertNull(parsed.tomlTable("name"))

        val mutable = KSLTomlCodec.mutableCopy(parsed)
        assertTrue(mutable.putTomlIfAbsent("newKey", 7))
        assertFalse(mutable.putTomlIfAbsent("newKey", 8))
        assertEquals(7, mutable.removeToml("newKey"))
    }

    @Test
    fun rejectsStandaloneOffsetTimeBecauseItIsNotATomlValue() {
        assertThrows(IllegalStateException::class.java) {
            KSLTomlCodec.encode(mapOf("server-time" to java.time.OffsetTime.parse("07:32:00Z")))
        }
    }

    @Test
    fun rejectsInvalidTomlAndUnsupportedNull() {
        assertThrows(IllegalArgumentException::class.java) { KSLTomlCodec.parse("a = [") }
        assertThrows(IllegalStateException::class.java) { KSLTomlCodec.encode(mapOf("nothing" to null)) }
    }
}
