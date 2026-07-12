package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KSLEntityManager(private val plugin: KotlinScriptLoaderPlugin) : Listener {

    private val ownerKey = NamespacedKey(plugin, "ksl_owner")

    private val entitiesByScript = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val removeOnReload = ConcurrentHashMap<UUID, Boolean>()
    private val deathCallbacks = ConcurrentHashMap<UUID, (LivingEntity, Player?) -> Unit>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun trackSpawn(scriptName: String, entity: LivingEntity, removeEntityOnReload: Boolean) {
        entity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, scriptName)
        entitiesByScript.computeIfAbsent(scriptName) { ConcurrentHashMap.newKeySet() }.add(entity.uniqueId)
        removeOnReload[entity.uniqueId] = removeEntityOnReload
    }

    fun setDeathCallback(entity: LivingEntity, callback: (LivingEntity, Player?) -> Unit) {
        deathCallbacks[entity.uniqueId] = callback
    }

    fun ownerOf(entity: LivingEntity): String? =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING)

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val uuid = event.entity.uniqueId
        removeOnReload.remove(uuid)

        val callback = deathCallbacks.remove(uuid) ?: return
        val owner = ownerOf(event.entity) ?: "unknown"
        try {
            callback(event.entity, event.entity.killer)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, owner, "entity onDeath", ex)
        }
    }

    fun cleanupForScript(scriptName: String) {
        val ids = entitiesByScript.remove(scriptName) ?: return
        ids.forEach { uuid ->
            deathCallbacks.remove(uuid)
            val shouldRemove = removeOnReload.remove(uuid) ?: true
            if (shouldRemove) {
                runCatching { Bukkit.getEntity(uuid)?.remove() }
                    .onFailure { plugin.logger.warning("[$scriptName] Не удалось убрать сущность $uuid при выгрузке: ${it.message}") }
            }
        }
    }
}
