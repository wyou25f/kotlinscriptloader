package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.scheduler.BukkitTask

class KSLEntityBehaviorBuilder(
    private val plugin: KotlinScriptLoaderPlugin,
    private val scriptName: String,
    private val entity: LivingEntity
) {

    private companion object {
        const val MAX_CONSECUTIVE_ERRORS = 5
    }

    fun onTick(period: Long, initialDelay: Long = 0L, block: (LivingEntity) -> Unit) {
        val validPeriod = KSLErrors.validPeriod(plugin, scriptName, "entity onTick", period)
        lateinit var task: BukkitTask
        var errors = 0

        fun stop() {
            task.cancel()
            plugin.untrackTask(scriptName, task.taskId)
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!entity.isValid || entity.isDead) {
                stop()
                return@Runnable
            }
            try {
                block(entity)
                errors = 0
            } catch (ex: Throwable) {
                KSLErrors.log(plugin, scriptName, "entity onTick", ex, KSLError.ENTITY_HOOK_FAILED)
                // A broken tick would otherwise log a stack trace 20 times a second for the mob's whole life.
                if (++errors >= MAX_CONSECUTIVE_ERRORS) {
                    plugin.logger.severe("[$scriptName] ${KSLError.ENTITY_TICK_DISABLED.tag} entity onTick отключён: $MAX_CONSECUTIVE_ERRORS ошибок подряд")
                    stop()
                }
            }
        }, initialDelay, validPeriod)
        plugin.trackTask(scriptName, task.taskId)
    }

    fun onDeath(block: (LivingEntity, Player?) -> Unit) {
        plugin.entityManager.setDeathCallback(scriptName, entity, block)
    }

    // The mob took damage (not cancelled by others). The event can be modified, e.g. event.damage = 1.0.
    fun onDamaged(block: (LivingEntity, EntityDamageEvent) -> Unit) {
        plugin.entityManager.setDamagedCallback(scriptName, entity, block)
    }

    // The mob hit someone (not cancelled by others). Projectile damage is not included.
    fun onAttack(block: (LivingEntity, EntityDamageByEntityEvent) -> Unit) {
        plugin.entityManager.setAttackCallback(scriptName, entity, block)
    }
}
