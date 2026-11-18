package ru.privateserver.ksl

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLYamlSchemaTest {
    @Test
    fun integerRulesAcceptIntegralYamlNumbersWithoutTruncatingOrOverflowing() {
        val yaml = YamlConfiguration().apply {
            set("intFromLong", 42L)
            set("longFromInt", 42)
            set("tooLargeForInt", Int.MAX_VALUE.toLong() + 1L)
            set("fractionalLong", 42.5)
            set("boundedLong", 10L)
        }
        val schema = KSLYamlSchema().apply {
            int("intFromLong", 0)
            long("longFromInt", 0L)
            int("tooLargeForInt", 7)
            long("fractionalLong", 8L)
            long("boundedLong", 0L, min = 10L, max = 20L)
        }

        val issues = schema.validate(yaml, applyDefaults = true)

        assertEquals(setOf("tooLargeForInt", "fractionalLong"), issues.map { it.path }.toSet())
        assertTrue(issues.all { it.corrected })
        assertEquals(42, yaml.getInt("intFromLong"))
        assertEquals(42L, yaml.getLong("longFromInt"))
        assertEquals(7, yaml.getInt("tooLargeForInt"))
        assertEquals(8L, yaml.getLong("fractionalLong"))
        assertEquals(10L, yaml.getLong("boundedLong"))
    }

    @Test
    fun validationWithoutDefaultsDoesNotMutateYaml() {
        val yaml = YamlConfiguration().apply { set("count", 1.5) }
        val issues = KSLYamlSchema().apply { long("count", 5L) }
            .validate(yaml, applyDefaults = false)

        assertEquals(1, issues.size)
        assertFalse(issues.single().corrected)
        assertEquals(1.5, yaml.getDouble("count"), 0.00001)
    }
}
