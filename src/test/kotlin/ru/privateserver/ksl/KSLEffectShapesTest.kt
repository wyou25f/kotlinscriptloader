package ru.privateserver.ksl

import org.bukkit.Location
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KSLEffectShapesTest {

    @Test
    fun `circle returns the expected finite points`() {
        val points = KSLEffectShapes.circle(radius = 2.0, stepDegrees = 15.0)
            .points(0L, Location(null, 0.0, 64.0, 0.0))

        assertEquals(24, points.size)
        assertTrue(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
    }

    @Test
    fun `line never exceeds the shared point limit`() {
        val center = Location(null, 0.0, 0.0, 0.0)
        val points = KSLEffectShapes.line({ Location(null, 10.0, 0.0, 0.0) }, Int.MAX_VALUE)
            .points(0L, center)

        assertEquals(KSLEffectBudget.MAX_POINTS, points.size)
    }

    @Test
    fun `sphere clamps an excessive point request`() {
        val points = KSLEffectShapes.sphere(1.0, Int.MAX_VALUE, 0.0)
            .points(0L, Location(null, 0.0, 0.0, 0.0))

        assertEquals(KSLEffectBudget.MAX_POINTS, points.size)
    }

    @Test
    fun `particle count is reduced to the per tick budget`() {
        assertEquals(5, KSLEffectBudget.countPerPoint(100, 360))
        assertEquals(100, KSLEffectBudget.countPerPoint(100, 1))
        assertEquals(0, KSLEffectBudget.countPerPoint(0, 360))
        assertThrows(IllegalArgumentException::class.java) {
            KSLEffectBudget.countPerPoint(1, KSLEffectBudget.MAX_POINTS + 1)
        }
    }

    @Test
    fun `shape inputs reject non finite geometry`() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLEffectShapes.circle(Double.NaN, 15.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLEffectShapes.sphere(Double.POSITIVE_INFINITY, 10, 0.0)
        }
    }
}
