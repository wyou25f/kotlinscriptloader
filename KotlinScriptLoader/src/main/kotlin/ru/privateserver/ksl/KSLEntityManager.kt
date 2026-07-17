package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
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
        val callback = deathCallbacks.remove(event.entity.uniqueId) ?: return
        val owner = ownerOf(event.entity) ?: "unknown"
        try {
            callback(event.entity, event.entity.killer)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, owner, "entity onDeath", ex)
        }
    }

    // Ловит вообще любую причину удаления сущности (смерть, /kill, деспавн по
    // расстоянию, снятие другим плагином) — не только выгрузку скрипта.
    // Без этого entitiesByScript рос бы бесконечно на сервере, где мобы
    // спавнятся и умирают естественным образом, а не только через reload.
    // ВАЖНО: не трогаем саму сущность (никакого .remove()) внутри этого
    // обработчика — Paper явно предупреждает, что это может уронить сервер
    // через ConcurrentModificationException, если удаление происходит
    // прямо во время итерации по списку сущностей мира.
    @EventHandler
    fun onRemove(event: EntityRemoveEvent) {
        val entity = event.entity as? LivingEntity ?: return
        val uuid = entity.uniqueId
        val owner = ownerOf(entity) ?: return
        entitiesByScript[owner]?.remove(uuid)
        removeOnReload.remove(uuid)
        deathCallbacks.remove(uuid)
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
