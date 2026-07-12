package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

class KSLEffectManager(private val plugin: KotlinScriptLoaderPlugin) {

    fun play(scriptName: String, spec: KSLEffectSpec): Int {
        val shape = spec.buildShape()
        var tick = 0L
        lateinit var task: BukkitTask

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (spec.durationTicks in 0..tick || spec.stopCondition?.invoke() == true) {
                task.cancel()
                return@Runnable
            }
            try {
                val center = spec.locationSupplier()
                val world = center.world
                if (world != null) {
                    shape.points(tick, center).forEach { offset ->
                        val point = center.clone().add(offset)
                        world.spawnParticle(spec.particle, point, spec.count, spec.offsetX, spec.offsetY, spec.offsetZ, spec.extra)
                    }
                }
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "playEffect tick", ex)
                task.cancel()
            }
            tick += spec.intervalTicks
        }, 0L, spec.intervalTicks)

        plugin.trackTask(scriptName, task.taskId)
        return task.taskId
    }
}
