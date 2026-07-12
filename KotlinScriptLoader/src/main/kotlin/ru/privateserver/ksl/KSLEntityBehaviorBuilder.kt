package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

class KSLEntityBehaviorBuilder(
    private val plugin: KotlinScriptLoaderPlugin,
    private val scriptName: String,
    private val entity: LivingEntity
) {

    fun onTick(period: Long, initialDelay: Long = 0L, block: (LivingEntity) -> Unit) {
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!entity.isValid || entity.isDead) {
                task.cancel()
                return@Runnable
            }
            try {
                block(entity)
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "entity onTick", ex)
            }
        }, initialDelay, period)
        plugin.trackTask(scriptName, task.taskId)
    }

    fun onDeath(block: (LivingEntity, Player?) -> Unit) {
        plugin.entityManager.setDeathCallback(entity, block)
    }
}
