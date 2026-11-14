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
    fun `arc preserves complete and multiple revolutions`() {
        val center = Location(null, 0.0, 0.0, 0.0)
        val full = KSLEffectShapes.arc(2.0, 0.0, 360.0, 5).points(0L, center)
        val double = KSLEffectShapes.arc(2.0, 0.0, 720.0, 5).points(0L, center)

        assertEquals(5, full.size)
        assertTrue(full.distinctBy { (it.x * 1000).toInt() to (it.z * 1000).toInt() }.size >= 4)
        assertEquals(full.first().x, full.last().x, 1.0e-9)
        assertEquals(full.first().z, full.last().z, 1.0e-9)
        assertTrue(double.distinctBy { (it.x * 1000).toInt() to (it.z * 1000).toInt() }.size >= 4)
    }

    @Test
    fun `all new named shapes are registered as built ins`() {
        val registry = KSLOwnedRegistry<KSLShapeFactory>()
        KSLEffectShapes.registerDefaults(registry)
        val center = Location(null, 0.0, 0.0, 0.0)
        val cases = mapOf(
            "arc" to mapOf("radius" to 2.0, "sweepDegrees" to 360.0, "points" to 12),
            "cylinder" to mapOf("radius" to 2.0, "height" to 3.0, "pointsPerRing" to 12),
            "cone" to mapOf("radius" to 2.0, "height" to 3.0, "points" to 12),
            "heart" to mapOf("size" to 2.0, "points" to 24)
        )
        cases.forEach { (name, params) ->
            val factory = registry.get(name)
            assertTrue(factory != null, "Missing built-in shape: $name")
            val points = factory!!.invoke(params, 40L).points(0L, center)
            assertTrue(points.isNotEmpty(), "Built-in shape returned no points: $name")
        }
    }

    @Test
    fun `arc cylinder cone and heart stay finite and bounded`() {
        val center = Location(null, 0.0, 0.0, 0.0)
        val shapes = listOf(
            KSLEffectShapes.arc(2.0, 0.0, 180.0, 30),
            KSLEffectShapes.cylinder(2.0, 3.0, 30),
            KSLEffectShapes.cone(2.0, 3.0, 30),
            KSLEffectShapes.heart(2.0, 80)
        )
        val expected = listOf(30, 60, 31, 80)
        shapes.zip(expected).forEach { (shape, count) ->
            val points = shape.points(0L, center)
            assertEquals(count, points.size)
            assertTrue(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        }
        assertEquals(KSLEffectBudget.MAX_POINTS,
            KSLEffectShapes.cylinder(1.0, 1.0, Int.MAX_VALUE).points(0L, center).size)
        assertEquals(KSLEffectBudget.MAX_POINTS,
            KSLEffectShapes.cone(1.0, 1.0, Int.MAX_VALUE).points(0L, center).size)
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
