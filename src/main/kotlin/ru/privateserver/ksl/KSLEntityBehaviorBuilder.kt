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
        require(initialDelay >= 0L) {
            "[$scriptName] entity onTick: initialDelay должен быть неотрицательным числом тиков"
        }
        val entityId = entity.uniqueId
        lateinit var task: BukkitTask
        var errors = 0

        fun stop() {
            task.cancel()
            plugin.untrackTask(scriptName, task.taskId)
            plugin.entityManager.untrackTickTask(scriptName, entityId, task.taskId)
        }

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            // Resolve by UUID every tick: the original Bukkit entity object may become invalid
            // when its chunk unloads. Keep the task dormant and resume with the reloaded entity.
            val current = Bukkit.getEntity(entityId) as? LivingEntity ?: return@Runnable
            if (current.isDead) {
                stop()
                return@Runnable
            }
            try {
                block(current)
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
        plugin.entityManager.trackTickTask(scriptName, entityId, task)
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
