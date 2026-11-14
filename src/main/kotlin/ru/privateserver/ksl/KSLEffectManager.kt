package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

class KSLEffectManager(private val plugin: KotlinScriptLoaderPlugin) {

    private companion object {
        // Per point; shapes already cap the point count, this caps the multiplier.
        const val MAX_COUNT = 100
        // A custom shape can return anything; a runaway list would freeze the tick.
        const val MAX_POINTS = 720
    }

    fun play(scriptName: String, spec: KSLEffectSpec): Int {
        val required = spec.particle.dataType
        if (required != Void::class.java) {
            val supplied = spec.data
            if (supplied == null) {
                throw IllegalArgumentException(
                    "[$scriptName] ${KSLError.EFFECT_DATA_REQUIRED.tag} playEffect: Particle.${spec.particle.name} требует data (${required.simpleName})"
                )
            }
            if (!required.isInstance(supplied)) {
                throw IllegalArgumentException(
                    "[$scriptName] playEffect: Particle.${spec.particle.name} ожидает ${required.simpleName}, получено ${supplied.javaClass.simpleName}"
                )
            }
        } else if (spec.data != null) {
            throw IllegalArgumentException(
                "[$scriptName] playEffect: Particle.${spec.particle.name} не принимает дополнительные данные"
            )
        }
        require(spec.durationTicks > 0L) { "[$scriptName] playEffect: durationTicks должен быть больше нуля" }
        require(
            spec.offsetX.isFinite() && spec.offsetY.isFinite() && spec.offsetZ.isFinite() && spec.extra.isFinite()
        ) { "[$scriptName] playEffect: offsetX/offsetY/offsetZ/extra должны быть конечными числами" }
        val shape = spec.buildShape()
        var tick = 0L
        lateinit var task: BukkitTask
        val interval = KSLErrors.validPeriod(plugin, scriptName, "playEffect", spec.intervalTicks)

        if (spec.count !in 0..MAX_COUNT)
            plugin.logger.warning("[$scriptName] playEffect: ${KSLError.EFFECT_COUNT_CLAMPED.tag} count=${spec.count} вне 0..$MAX_COUNT — значение будет ограничено")

        var stopped = false
        fun stop() {
            if (stopped) return
            stopped = true
            task.cancel()
            plugin.untrackTask(scriptName, task.taskId)
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                // Inside the try: a throwing stopCondition would otherwise repeat the error every tick forever.
                if (tick >= spec.durationTicks || spec.stopCondition?.invoke() == true) {
                    stop()
                    spec.finish()
                    return@Runnable
                }
                val center = spec.locationSupplier()
                val world = center.world
                if (world != null) {
                    val count = spec.count.coerceIn(0, MAX_COUNT)
                    // Plain coordinates: one Location per point per tick adds up with large shapes and many effects.
                    val cx = center.x
                    val cy = center.y
                    val cz = center.z
                    val points = shape.points(tick, center)
                    require(points.size <= MAX_POINTS) {
                        "Форма эффекта вернула ${points.size} точек; максимум — $MAX_POINTS"
                    }
                    points.forEach { offset ->
                        require(offset.x.isFinite() && offset.y.isFinite() && offset.z.isFinite()) {
                            "Форма эффекта вернула координаты, не являющиеся конечными числами"
                        }
                        val x = cx + offset.x
                        val y = cy + offset.y
                        val z = cz + offset.z
                        require(x.isFinite() && y.isFinite() && z.isFinite()) {
                            "Итоговые координаты эффекта вышли за допустимый числовой диапазон"
                        }
                        world.spawnParticle(spec.particle, x, y, z, count, spec.offsetX, spec.offsetY, spec.offsetZ, spec.extra, spec.data)
                    }
                }
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "playEffect tick", ex, KSLError.EFFECT_TICK_FAILED)
                stop()
                return@Runnable
            }
            // Saturate at the requested duration instead of overflowing Long and keeping
            // an extremely long-running effect alive forever.
            tick = if (tick > Long.MAX_VALUE - interval) spec.durationTicks else tick + interval
        }, 0L, interval)

        plugin.trackTask(scriptName, task.taskId)
        return task.taskId
    }
}
