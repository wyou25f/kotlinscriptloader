package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KSLEntityManager(private val plugin: KotlinScriptLoaderPlugin) : Listener {

    // Keyed by entity, but owned by a script: the script name is needed to drop the hook on unload,
    // otherwise a stale lambda keeps the old script classloader alive and still fires after reload.
    private class DeathHook(val scriptName: String, val callback: (LivingEntity, Player?) -> Unit)

    private val ownerKey = NamespacedKey(plugin, "ksl_owner")
    private val ephemeralKey = NamespacedKey(plugin, "ksl_ephemeral")

    private val entitiesByScript = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val removeOnReload = ConcurrentHashMap<UUID, Boolean>()
    private val deathCallbacks = ConcurrentHashMap<UUID, DeathHook>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun scriptNames(): Set<String> = entitiesByScript.keys + deathCallbacks.values.map { it.scriptName }

    fun trackSpawn(scriptName: String, entity: LivingEntity, removeEntityOnReload: Boolean) {
        val pdc = entity.persistentDataContainer
        pdc.set(ownerKey, PersistentDataType.STRING, scriptName)
        // Survives restarts, unlike the in-memory index; lets onEntitiesLoad spot husks left behind.
        if (removeEntityOnReload) pdc.set(ephemeralKey, PersistentDataType.BYTE, 1.toByte())
        entitiesByScript.computeIfAbsent(scriptName) { ConcurrentHashMap.newKeySet() }.add(entity.uniqueId)
        removeOnReload[entity.uniqueId] = removeEntityOnReload
    }

    // Used when spawning fails half-way: the entity is being removed, so nothing should reference it.
    fun forget(scriptName: String, entity: LivingEntity) {
        val uuid = entity.uniqueId
        entitiesByScript[scriptName]?.remove(uuid)
        removeOnReload.remove(uuid)
        deathCallbacks.remove(uuid)
    }

    fun setDeathCallback(scriptName: String, entity: LivingEntity, callback: (LivingEntity, Player?) -> Unit) {
        deathCallbacks[entity.uniqueId] = DeathHook(scriptName, callback)
    }

    fun ownerOf(entity: LivingEntity): String? =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING)

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val hook = deathCallbacks.remove(event.entity.uniqueId) ?: return
        try {
            hook.callback(event.entity, event.entity.killer)
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, hook.scriptName, "entity onDeath", ex)
        }
    }

    // Catches every removal cause (death, /kill, distance despawn, other plugins), not only script unload.
    // Without it the maps would grow forever on a server where mobs spawn and die naturally.
    // NOTE: never touch the entity itself (no .remove()) in here; Paper can throw
    // ConcurrentModificationException if removal happens while the world is iterating its entities.
    @EventHandler
    fun onRemove(event: EntityRemoveEvent) {
        val entity = event.entity as? LivingEntity ?: return
        // Saved with the chunk and coming back under the same UUID: keep the hooks and the index entry.
        if (event.cause == EntityRemoveEvent.Cause.UNLOAD && entity.isPersistent) return
        val uuid = entity.uniqueId
        // Hooks can exist for entities we never spawned, so this must not depend on the owner tag.
        deathCallbacks.remove(uuid)
        removeOnReload.remove(uuid)
        ownerOf(entity)?.let { entitiesByScript[it]?.remove(uuid) }
    }

    // Saved entities outlive the in-memory index (restart, script unloaded while the chunk was unloaded).
    // An ephemeral one that nobody tracks has lost its script behavior; drop it instead of leaving an inert mob.
    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (!plugin.isEnabled) return
        val orphans = event.entities.filter { entity ->
            val pdc = entity.persistentDataContainer
            val owner = pdc.get(ownerKey, PersistentDataType.STRING) ?: return@filter false
            pdc.has(ephemeralKey, PersistentDataType.BYTE) && entitiesByScript[owner]?.contains(entity.uniqueId) != true
        }
        if (orphans.isEmpty()) return
        // Removal is deferred: the entities are still being added to the world inside this event.
        Bukkit.getScheduler().runTask(plugin, Runnable { orphans.forEach { if (it.isValid) it.remove() } })
    }

    fun cleanupForScript(scriptName: String) {
        deathCallbacks.values.removeIf { it.scriptName == scriptName }
        val ids = entitiesByScript.remove(scriptName) ?: return
        ids.forEach { uuid ->
            val shouldRemove = removeOnReload.remove(uuid) ?: true
            if (shouldRemove) {
                runCatching { Bukkit.getEntity(uuid)?.remove() }
                    .onFailure { plugin.logger.warning("[$scriptName] Не удалось убрать сущность $uuid при выгрузке: ${it.message}") }
            }
        }
    }
}
