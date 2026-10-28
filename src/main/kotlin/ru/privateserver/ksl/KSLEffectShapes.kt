package ru.privateserver.ksl

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun interface KSLEffectShape {
    fun points(tick: Long, center: Location): List<Vector>
}

// Builds a shape from script parameters: shape("name", "radius" to 2.0). Read values with num()/int().
typealias KSLShapeFactory = (params: Map<String, Any?>, durationTicks: Long) -> KSLEffectShape

fun Map<String, Any?>.num(key: String, default: Double): Double = (this[key] as? Number)?.toDouble() ?: default

fun Map<String, Any?>.int(key: String, default: Int): Int = (this[key] as? Number)?.toInt() ?: default

fun Map<String, Any?>.long(key: String, default: Long): Long = (this[key] as? Number)?.toLong() ?: default

fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = (this[key] as? Boolean) ?: default

fun Map<String, Any?>.str(key: String, default: String): String = this[key]?.toString() ?: default

object KSLEffectShapes {

    private const val MAX_POINTS = 360

    fun circle(radius: Double, stepDegrees: Double): KSLEffectShape {
        val safeStep = if (stepDegrees > 0.0) stepDegrees else 15.0
        val pointCount = (360.0 / safeStep).toInt().coerceIn(4, MAX_POINTS)
        return KSLEffectShape { tick, _ ->
            val phase = Math.toRadians(tick * safeStep)
            (0 until pointCount).map { i ->
                val angle = phase + Math.toRadians(i * (360.0 / pointCount))
                Vector(cos(angle) * radius, 0.0, sin(angle) * radius)
            }
        }
    }

    fun helix(radius: Double, turns: Double, height: Double, totalTicks: Long): KSLEffectShape {
        return KSLEffectShape { tick, _ ->
            val progress = if (totalTicks > 0) (tick.toDouble() / totalTicks).coerceIn(0.0, 1.0) else 0.0
            val angle = Math.toRadians(progress * turns * 360.0)
            val y = height * progress
            val angle2 = angle + Math.PI
            listOf(
                Vector(cos(angle) * radius, y, sin(angle) * radius),
                Vector(cos(angle2) * radius, y, sin(angle2) * radius)
            )
        }
    }

    fun line(target: () -> Location, points: Int): KSLEffectShape {
        val steps = points.coerceIn(1, MAX_POINTS)
        return KSLEffectShape { _, center ->
            val to = target()
            if (to.world != center.world) return@KSLEffectShape emptyList()
            val delta = to.toVector().subtract(center.toVector())
            (0..steps).map { i -> delta.clone().multiply(i.toDouble() / steps) }
        }
    }

    // Evenly spread points (Fibonacci lattice); spinDegreesPerTick rotates the whole sphere around Y.
    fun sphere(radius: Double, points: Int, spinDegreesPerTick: Double): KSLEffectShape {
        val n = points.coerceIn(4, MAX_POINTS)
        val golden = Math.PI * (3.0 - sqrt(5.0))
        val base = (0 until n).map { i ->
            val y = 1.0 - 2.0 * (i + 0.5) / n
            val ring = sqrt(1.0 - y * y)
            val theta = golden * i
            Vector(cos(theta) * ring * radius, y * radius, sin(theta) * ring * radius)
        }
        if (spinDegreesPerTick == 0.0) return KSLEffectShape { _, _ -> base }
        return KSLEffectShape { tick, _ ->
            val spin = Math.toRadians(tick * spinDegreesPerTick)
            base.map { it.clone().rotateAroundY(spin) }
        }
    }

    // Perimeter of a square lying flat, centred on the effect location.
    fun square(size: Double, pointsPerSide: Int): KSLEffectShape {
        val n = pointsPerSide.coerceIn(1, MAX_POINTS / 4)
        val h = size / 2.0
        val perimeter = ArrayList<Vector>(n * 4)
        for (i in 0 until n) {
            val t = -h + size * i / n
            perimeter += Vector(t, 0.0, -h)
            perimeter += Vector(h, 0.0, t)
            perimeter += Vector(-t, 0.0, h)
            perimeter += Vector(-h, 0.0, -t)
        }
        return KSLEffectShape { _, _ -> perimeter }
    }

    fun point(): KSLEffectShape = KSLEffectShape { _, _ -> listOf(Vector(0.0, 0.0, 0.0)) }

    // Built-ins are registered like addon shapes, so shape("circle", "radius" to 2.0) works everywhere.
    fun registerDefaults(registry: KSLOwnedRegistry<KSLShapeFactory>) {
        registry.put("circle", { p, _ -> circle(p.num("radius", 1.0), p.num("step", 15.0)) }, null)
        registry.put("helix", { p, total -> helix(p.num("radius", 1.0), p.num("turns", 3.0), p.num("height", 3.0), total) }, null)
        registry.put("sphere", { p, _ -> sphere(p.num("radius", 1.0), p.int("points", 60), p.num("spin", 0.0)) }, null)
        registry.put("square", { p, _ -> square(p.num("size", 2.0), p.int("points", 10)) }, null)
        registry.put("point", { _, _ -> point() }, null)
    }
}
