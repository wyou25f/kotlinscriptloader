package ru.privateserver.ksl

import org.bukkit.Location
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

fun interface KSLEffectShape {
    fun points(tick: Long, center: Location): List<Vector>
}

object KSLEffectShapes {

    fun circle(radius: Double, stepDegrees: Double): KSLEffectShape {
        val pointCount = maxOf(4, (360.0 / stepDegrees).toInt())
        return KSLEffectShape { tick, _ ->
            val phase = Math.toRadians(tick * stepDegrees)
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
        val steps = maxOf(1, points)
        return KSLEffectShape { _, center ->
            val to = target()
            if (to.world != center.world) return@KSLEffectShape emptyList()
            val delta = to.toVector().subtract(center.toVector())
            (0..steps).map { i -> delta.clone().multiply(i.toDouble() / steps) }
        }
    }
}
