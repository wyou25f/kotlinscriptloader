package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

class KSLEffectManager(private val plugin: KotlinScriptLoaderPlugin) {

    private companion object {
        // Per point; shapes already cap the point count, this caps the multiplier.
        const val MAX_COUNT = 100
    }

    fun play(scriptName: String, spec: KSLEffectSpec): Int {
        val shape = spec.buildShape()
        var tick = 0L
        lateinit var task: BukkitTask
        val interval = KSLErrors.validPeriod(plugin, scriptName, "playEffect", spec.intervalTicks)

        if (spec.count !in 0..MAX_COUNT)
            plugin.logger.warning("[$scriptName] playEffect: count=${spec.count} вне 0..$MAX_COUNT — значение будет ограничено")

        fun stop() {
            task.cancel()
            plugin.untrackTask(scriptName, task.taskId)
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try {
                // Inside the try: a throwing stopCondition would otherwise repeat the error every tick forever.
                if (tick >= spec.durationTicks || spec.stopCondition?.invoke() == true) {
                    stop()
                    return@Runnable
                }
                val center = spec.locationSupplier()
                val world = center.world
                if (world != null) {
                    val count = spec.count.coerceIn(0, MAX_COUNT)
                    shape.points(tick, center).forEach { offset ->
                        val point = center.clone().add(offset)
                        world.spawnParticle(spec.particle, point, count, spec.offsetX, spec.offsetY, spec.offsetZ, spec.extra)
                    }
                }
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "playEffect tick", ex)
                stop()
                return@Runnable
            }
            tick += interval
        }, 0L, interval)

        plugin.trackTask(scriptName, task.taskId)
        return task.taskId
    }
}
