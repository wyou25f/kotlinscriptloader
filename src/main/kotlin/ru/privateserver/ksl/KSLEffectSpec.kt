package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.inventory.ItemStack
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
    var data: Any? = null

    internal var originOffset = Vector(0.0, 0.0, 0.0)
    internal var rotationDegrees = 0.0
    internal var spinDegreesPerTick = 0.0
    internal var stopCondition: (() -> Boolean)? = null

    private var endCallback: (() -> Unit)? = null
    private var shapeBuilder: ((Long) -> KSLEffectShape)? = null

    fun amount(value: Int) {
        require(value in 0..KSLEffectBudget.MAX_COUNT) { "Количество частиц должно быть от 0 до " + KSLEffectBudget.MAX_COUNT }
        count = value
    }

    fun particles(value: Int) = amount(value)

    fun spread(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Разброс частиц должен состоять из конечных чисел" }
        offsetX = x
        offsetY = y
        offsetZ = z
    }

    fun origin(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Смещение эффекта должно состоять из конечных чисел" }
        originOffset = Vector(x, y, z)
    }

    fun speed(value: Double) {
        require(value.isFinite()) { "Скорость частиц должна быть конечным числом" }
        extra = value
    }

    fun every(ticks: Long) {
        require(ticks > 0L) { "Интервал эффекта должен быть больше нуля тиков" }
        intervalTicks = ticks
    }

    fun forTicks(ticks: Long) {
        require(ticks > 0L) { "Длительность эффекта должна быть больше нуля тиков" }
        durationTicks = ticks
    }

    fun rotate(degrees: Double) {
        require(degrees.isFinite()) { "Угол вращения должен быть конечным числом" }
        rotationDegrees = degrees
    }

    fun spin(degreesPerTick: Double) {
        require(degreesPerTick.isFinite()) { "Скорость вращения должна быть конечным числом" }
        spinDegreesPerTick = degreesPerTick
    }

    fun circle(radius: Double, stepDegrees: Double = 15.0) {
        shapeBuilder = { KSLEffectShapes.circle(radius, stepDegrees) }
    }

    fun ring(radius: Double, points: Int = 24) {
        require(points >= 4) { "Кольцу требуется минимум 4 точки" }
        shapeBuilder = { KSLEffectShapes.circle(radius, 360.0 / points) }
    }

    fun helix(radius: Double, turns: Double = 3.0, height: Double = 3.0) {
        shapeBuilder = { totalTicks -> KSLEffectShapes.helix(radius, turns, height, totalTicks) }
    }

    fun spiral(radius: Double, turns: Double = 3.0, height: Double = 3.0) =
        helix(radius, turns, height)

    fun lineTo(target: () -> Location, points: Int = 20) {
        shapeBuilder = { KSLEffectShapes.line(target, points) }
    }

    fun sphere(radius: Double, points: Int = 60, spinDegreesPerTick: Double = 0.0) {
        shapeBuilder = { KSLEffectShapes.sphere(radius, points, spinDegreesPerTick) }
    }

    fun square(size: Double, pointsPerSide: Int = 10) {
        shapeBuilder = { KSLEffectShapes.square(size, pointsPerSide) }
    }

    fun arc(radius: Double, startDegrees: Double = 0.0, sweepDegrees: Double = 180.0, points: Int = 30) {
        shapeBuilder = { KSLEffectShapes.arc(radius, startDegrees, sweepDegrees, points) }
    }

    fun cylinder(radius: Double, height: Double, pointsPerRing: Int = 30) {
        shapeBuilder = { KSLEffectShapes.cylinder(radius, height, pointsPerRing) }
    }

    fun cone(radius: Double, height: Double, points: Int = 60) {
        shapeBuilder = { KSLEffectShapes.cone(radius, height, points) }
    }

    fun heart(size: Double = 1.0, points: Int = 80) {
        shapeBuilder = { KSLEffectShapes.heart(size, points) }
    }

    fun box(width: Double, height: Double, depth: Double, pointsPerEdge: Int = 8) {
        shapeBuilder = { KSLEffectShapes.box(width, height, depth, pointsPerEdge) }
    }

    fun cube(size: Double = 2.0, pointsPerEdge: Int = 8) =
        box(size, size, size, pointsPerEdge)

    fun point() {
        shapeBuilder = { KSLEffectShapes.point() }
    }

    fun custom(points: (tick: Long, center: Location) -> List<Vector>) {
        shapeBuilder = { KSLEffectShape { tick, center -> points(tick, center) } }
    }

    fun shape(name: String, vararg params: Pair<String, Any?>) {
        val factory = shapes?.get(name.lowercase())
            ?: error(KSLError.EFFECT_UNKNOWN_SHAPE.tag + " Неизвестная форма эффекта '" + name +
                "'. Доступны: " + (shapes?.keys()?.sorted()?.joinToString(", ") ?: "—"))
        val map = params.toMap()
        shapeBuilder = { totalTicks -> factory(map, totalTicks) }
    }

    private fun validatedDustSize(size: Float): Float {
        require(size.isFinite() && size in 0.01f..4.0f) { "Размер Dust должен быть конечным числом от 0.01 до 4.0" }
        return size
    }

    private fun parseHexColor(hex: String): Color {
        val normalized = hex.removePrefix("#")
        require(normalized.matches(Regex("[0-9A-Fa-f]{6}"))) {
            KSLError.EFFECT_BAD_COLOR.tag + " Ожидался HEX вида #RRGGBB, получено '" + hex + "'"
        }
        return Color.fromRGB(normalized.toInt(16))
    }

    fun color(r: Int, g: Int, b: Int, size: Float = 1f) {
        data = Particle.DustOptions(
            Color.fromRGB(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)),
            validatedDustSize(size)
        )
    }

    fun color(hex: String, size: Float = 1f) {
        data = Particle.DustOptions(parseHexColor(hex), validatedDustSize(size))
    }

    fun dust(hex: String, size: Float = 1f) = color(hex, size)

    fun transition(fromHex: String, toHex: String, size: Float = 1f) {
        val from = parseHexColor(fromHex)
        val to = parseHexColor(toHex)
        val dustSize = validatedDustSize(size)
        val transitionClassName = "org.bukkit.Particle" + 36.toChar() + "DustTransition"
        val transitionType = try {
            Class.forName(transitionClassName, false, Particle::class.java.classLoader)
        } catch (ex: ClassNotFoundException) {
            throw UnsupportedOperationException(
                "Particle color transitions require Paper 1.18.2 or newer; this server provides no Particle.DustTransition",
                ex
            )
        }
        data = try {
            transitionType.getConstructor(Color::class.java, Color::class.java, java.lang.Float.TYPE)
                .newInstance(from, to, dustSize)
        } catch (ex: ReflectiveOperationException) {
            throw IllegalStateException("This server exposes Particle.DustTransition but its constructor is incompatible", ex)
        }
    }

    fun block(material: Material) {
        require(material.isBlock) { "Для block(data) нужен материал-блок, получено " + material }
        data = Bukkit.createBlockData(material)
    }

    fun item(stack: ItemStack) {
        require(!stack.type.isAir) { "Для item(data) нужен непустой предмет" }
        data = stack.clone()
    }

    fun particleData(value: Any?) {
        data = if (value is ItemStack) value.clone() else value
    }

    fun stopWhen(condition: () -> Boolean) {
        val previous = stopCondition
        stopCondition = if (previous == null) condition else {
            val combined: () -> Boolean = { previous() || condition() }
            combined
        }
    }

    fun onEnd(block: () -> Unit) {
        endCallback = block
    }

    internal fun finish() {
        endCallback?.invoke()
    }

    internal fun buildShape(): KSLEffectShape {
        require(durationTicks > 0L) { "durationTicks должен быть больше нуля" }
        return shapeBuilder?.invoke(durationTicks) ?: KSLEffectShapes.circle(1.0, 30.0)
    }
}
