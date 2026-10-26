package ru.privateserver.ksl

import org.bukkit.Location
import org.bukkit.Particle

class KSLEffectSpec(val particle: Particle, internal val locationSupplier: () -> Location) {

    var count: Int = 1
    var offsetX: Double = 0.0
    var offsetY: Double = 0.0
    var offsetZ: Double = 0.0
    var extra: Double = 0.0
    var durationTicks: Long = 100L
    var intervalTicks: Long = 2L

    internal var stopCondition: (() -> Boolean)? = null

    private var shapeBuilder: ((Long) -> KSLEffectShape)? = null

    fun circle(radius: Double, stepDegrees: Double = 15.0) {
        shapeBuilder = { KSLEffectShapes.circle(radius, stepDegrees) }
    }

    fun helix(radius: Double, turns: Double = 3.0, height: Double = 3.0) {
        shapeBuilder = { totalTicks -> KSLEffectShapes.helix(radius, turns, height, totalTicks) }
    }

    fun lineTo(target: () -> Location, points: Int = 20) {
        shapeBuilder = { KSLEffectShapes.line(target, points) }
    }

    internal fun buildShape(): KSLEffectShape =
        shapeBuilder?.invoke(durationTicks) ?: KSLEffectShapes.circle(1.0, 30.0)
}
