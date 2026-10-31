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
        if (required != Void::class.java && spec.data == null) {
            throw IllegalArgumentException(
                "[$scriptName] ${KSLError.EFFECT_DATA_REQUIRED.tag} playEffect: Particle.${spec.particle.name} требует data (${required.simpleName}) — " +
                    "для DUST вызови color(255, 0, 0), для остальных присвой data = ..."
            )
        }
        val shape = spec.buildShape()
        var tick = 0L
        lateinit var task: BukkitTask
        val interval = KSLErrors.validPeriod(plugin, scriptName, "playEffect", spec.intervalTicks)

        if (spec.count !in 0..MAX_COUNT)
            plugin.logger.warning("[$scriptName] playEffect: ${KSLError.EFFECT_COUNT_CLAMPED.tag} count=${spec.count} вне 0..$MAX_COUNT — значение будет ограничено")

        fun stop() {
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
                    shape.points(tick, center).take(MAX_POINTS).forEach { offset ->
                        world.spawnParticle(spec.particle, cx + offset.x, cy + offset.y, cz + offset.z, count, spec.offsetX, spec.offsetY, spec.offsetZ, spec.extra, spec.data)
                    }
                }
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "playEffect tick", ex, KSLError.EFFECT_TICK_FAILED)
                stop()
                return@Runnable
            }
            tick += interval
        }, 0L, interval)

        plugin.trackTask(scriptName, task.taskId)
        return task.taskId
    }
}
