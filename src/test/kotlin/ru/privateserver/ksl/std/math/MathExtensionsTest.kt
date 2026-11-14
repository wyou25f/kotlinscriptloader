package ru.privateserver.ksl.std.math

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MathExtensionsTest {
    @Test
    fun clampBoundsAndValidation() {
        assertEquals(3, 1.clamped(3, 8))
        assertEquals(8L, 10L.clamped(0L, 8L))
        assertEquals(2.5, 1.0.clamped(2.5, 5.0), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { 1.clamped(5, 2) }
        assertThrows(IllegalArgumentException::class.java) { Double.NaN.clamped(0.0, 1.0) }
    }

    @Test
    fun rangeMappingSupportsReverseRangesAndExtrapolation() {
        assertEquals(50.0, 5.0.mapToRange(0.0, 10.0, 0.0, 100.0), 1e-9)
        assertEquals(5.0, 5.0.mapToRange(0.0, 10.0, 10.0, 0.0), 1e-9)
        assertEquals(150.0, 15.0.mapToRange(0.0, 10.0, 0.0, 100.0), 1e-9)
        assertThrows(IllegalArgumentException::class.java) { 1.0.mapToRange(2.0, 2.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) {
            Double.POSITIVE_INFINITY.mapToRange(0.0, 1.0, 0.0, 1.0)
        }
    }

    @Test
    fun interpolationHandlesExtremeFiniteBounds() {
        assertEquals(0.5, 0.0.mapToRange(-Double.MAX_VALUE, Double.MAX_VALUE, 0.0, 1.0), 1e-9)
        assertEquals(0.0, lerp(-Double.MAX_VALUE, Double.MAX_VALUE, 0.5), 1e-9)
        assertEquals(0.5, inverseLerp(-Double.MAX_VALUE, Double.MAX_VALUE, 0.0), 1e-9)
        assertEquals(1.0, inverseLerp(10.0, 0.0, 0.0), 1e-9)
        assertEquals(0.0, inverseLerp(10.0, 0.0, 20.0, clamp = true), 1e-9)
    }

    @Test
    fun randomHelpersValidateBoundsAndReturnInRange() {
        assertEquals(2, randomInt(2, 3))
        assertEquals(5L, randomLong(5L, 6L))
        val sample = randomDouble(-2.0, 2.0)
        assertTrue(sample >= -2.0 && sample < 2.0)
        assertThrows(IllegalArgumentException::class.java) { randomInt(1, 1) }
        assertThrows(IllegalArgumentException::class.java) { randomLong(2L, 1L) }
        assertThrows(IllegalArgumentException::class.java) { randomDouble(Double.NaN, 1.0) }

        repeat(100) {
            val wide = randomDouble(-Double.MAX_VALUE, Double.MAX_VALUE)
            assertTrue(wide >= -Double.MAX_VALUE && wide < Double.MAX_VALUE)
        }
        val positiveWide = randomDouble(Double.MAX_VALUE / 2.0, Double.MAX_VALUE)
        assertTrue(positiveWide >= Double.MAX_VALUE / 2.0 && positiveWide < Double.MAX_VALUE)
    }

    @Test
    fun randomChanceValidatesPercentagesAndDeterministicBounds() {
        assertTrue(randomChance(100.0))
        assertFalse(randomChance(0.0))
        assertThrows(IllegalArgumentException::class.java) { randomChance(-0.1) }
        assertThrows(IllegalArgumentException::class.java) { randomChance(100.1) }
        assertThrows(IllegalArgumentException::class.java) { randomChance(Double.NaN) }
    }
}
