package ru.privateserver.ksl

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.util.Vector

class KSLEffectSpec(
    val particle: Particle,
    internal val locationSupplier: () -> Location,
    private val shapes: KSLOwnedRegistry<KSLShapeFactory>? = null
) {

    var count: Int = 1
    var offsetX: Double = 0.0
    var offsetY: Double = 0.0
    var offsetZ: Double = 0.0
    var extra: Double = 0.0
    var durationTicks: Long = 100L
    var intervalTicks: Long = 2L

    // Extra data some particles require: DustOptions for DUST (see color), BlockData for BLOCK, and so on.
    var data: Any? = null

    internal var stopCondition: (() -> Boolean)? = null

    private var endCallback: (() -> Unit)? = null

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

    fun sphere(radius: Double, points: Int = 60, spinDegreesPerTick: Double = 0.0) {
        shapeBuilder = { KSLEffectShapes.sphere(radius, points, spinDegreesPerTick) }
    }

    fun square(size: Double, pointsPerSide: Int = 10) {
        shapeBuilder = { KSLEffectShapes.square(size, pointsPerSide) }
    }

    fun point() {
        shapeBuilder = { KSLEffectShapes.point() }
    }

    // Inline custom shape: return offsets from the effect location for the given tick.
    fun custom(points: (tick: Long, center: Location) -> List<Vector>) {
        shapeBuilder = { KSLEffectShape { tick, center -> points(tick, center) } }
    }

    // A shape by name, built-in or contributed by an addon: shape("sphere", "radius" to 2.0, "spin" to 6.0).
    fun shape(name: String, vararg params: Pair<String, Any?>) {
        val factory = shapes?.get(name.lowercase())
            ?: error("${KSLError.EFFECT_UNKNOWN_SHAPE.tag} Неизвестная форма эффекта '$name'. Доступны: ${shapes?.keys()?.sorted()?.joinToString(", ") ?: "—"}")
        val map = params.toMap()
        shapeBuilder = { totalTicks -> factory(map, totalTicks) }
    }

    fun color(r: Int, g: Int, b: Int, size: Float = 1f) {
        data = Particle.DustOptions(Color.fromRGB(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)), size)
    }

    fun color(hex: String, size: Float = 1f) {
        val rgb = hex.removePrefix("#").toIntOrNull(16)
            ?: throw IllegalArgumentException("${KSLError.EFFECT_BAD_COLOR.tag} color: ожидался HEX вида #RRGGBB, получено '$hex'")
        data = Particle.DustOptions(Color.fromRGB(rgb and 0xFFFFFF), size)
    }

    // Several conditions combine with OR; a following entity's own "still valid" check stays in effect.
    fun stopWhen(condition: () -> Boolean) {
        val previous = stopCondition
        stopCondition = if (previous == null) condition else {
            val combined: () -> Boolean = { previous() || condition() }
            combined
        }
    }

    // Runs once when the effect ends by duration or stopWhen (not when the script unloads).
    fun onEnd(block: () -> Unit) {
        endCallback = block
    }

    internal fun finish() {
        endCallback?.invoke()
    }

    internal fun buildShape(): KSLEffectShape =
        shapeBuilder?.invoke(durationTicks) ?: KSLEffectShapes.circle(1.0, 30.0)
}
