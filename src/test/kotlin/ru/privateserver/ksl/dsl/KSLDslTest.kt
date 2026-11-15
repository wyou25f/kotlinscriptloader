package ru.privateserver.ksl.dsl

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLDslTest {
    @Test
    fun chanceValidatesFinitePercentageBounds() {
        assertTrue(chance(100.0))
        assertFalse(chance(0.0))
        assertThrows(IllegalArgumentException::class.java) { chance(-0.1) }
        assertThrows(IllegalArgumentException::class.java) { chance(100.1) }
        assertThrows(IllegalArgumentException::class.java) { chance(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { chance(Double.POSITIVE_INFINITY) }
    }
}
