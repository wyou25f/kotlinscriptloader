package ru.privateserver.ksl.std.toml

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
    fun rejectsInvalidTomlAndUnsupportedNull() {
        assertThrows(IllegalArgumentException::class.java) { KSLTomlCodec.parse("a = [") }
        assertThrows(IllegalStateException::class.java) { KSLTomlCodec.encode(mapOf("nothing" to null)) }
    }
}
