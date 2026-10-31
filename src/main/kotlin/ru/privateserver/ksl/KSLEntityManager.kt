package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class KSLEntityManager(private val plugin: KotlinScriptLoaderPlugin) : Listener {

    // Keyed by entity, but owned by a script: the script name is needed to drop the hook on unload,
    // otherwise a stale lambda keeps the old script classloader alive and still fires after reload.
    private class Hook<T>(val scriptName: String, val callback: T)

    private class DropSpec(val scriptName: String, val items: List<ItemStack>, val exp: Int?, val keepDefault: Boolean)

    private val ownerKey = NamespacedKey(plugin, "ksl_owner")
    private val ephemeralKey = NamespacedKey(plugin, "ksl_ephemeral")

    private val entitiesByScript = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val removeOnReload = ConcurrentHashMap<UUID, Boolean>()

    private val deathHooks = ConcurrentHashMap<UUID, Hook<(LivingEntity, Player?) -> Unit>>()
    private val damagedHooks = ConcurrentHashMap<UUID, Hook<(LivingEntity, EntityDamageEvent) -> Unit>>()
    private val attackHooks = ConcurrentHashMap<UUID, Hook<(LivingEntity, EntityDamageByEntityEvent) -> Unit>>()
    private val dropSpecs = ConcurrentHashMap<UUID, DropSpec>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun scriptNames(): Set<String> = entitiesByScript.keys +
        deathHooks.values.map { it.scriptName } +
        damagedHooks.values.map { it.scriptName } +
        attackHooks.values.map { it.scriptName } +
        dropSpecs.values.map { it.scriptName }

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
        dropHooks(uuid)
    }

    private fun dropHooks(uuid: UUID) {
        deathHooks.remove(uuid)
        damagedHooks.remove(uuid)
        attackHooks.remove(uuid)
        dropSpecs.remove(uuid)
    }

    fun setDeathCallback(scriptName: String, entity: LivingEntity, callback: (LivingEntity, Player?) -> Unit) {
        deathHooks[entity.uniqueId] = Hook(scriptName, callback)
    }

    fun setDamagedCallback(scriptName: String, entity: LivingEntity, callback: (LivingEntity, EntityDamageEvent) -> Unit) {
        damagedHooks[entity.uniqueId] = Hook(scriptName, callback)
    }

    fun setAttackCallback(scriptName: String, entity: LivingEntity, callback: (LivingEntity, EntityDamageByEntityEvent) -> Unit) {
        attackHooks[entity.uniqueId] = Hook(scriptName, callback)
    }

    fun setDrops(scriptName: String, entity: LivingEntity, items: List<ItemStack>, exp: Int?, keepDefault: Boolean) {
        dropSpecs[entity.uniqueId] = DropSpec(scriptName, items.map { it.clone() }, exp, keepDefault)
    }

    fun ownerOf(entity: LivingEntity): String? =
        entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING)

    private inline fun runHook(scriptName: String, where: String, block: () -> Unit) {
        try {
            block()
        } catch (ex: Throwable) {
            KSLErrors.log(plugin, scriptName, where, ex, KSLError.ENTITY_HOOK_FAILED)
        }
    }

    // Death, damage and removal events fire for every entity on the server; with no scripted mobs they should cost nothing.
    private fun hooksIdle(): Boolean =
        deathHooks.isEmpty() && damagedHooks.isEmpty() && attackHooks.isEmpty() && dropSpecs.isEmpty()

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        if (hooksIdle()) return
        val uuid = event.entity.uniqueId
        dropSpecs.remove(uuid)?.let { spec ->
            if (!spec.keepDefault) event.drops.clear()
            event.drops.addAll(spec.items.map { it.clone() })
            spec.exp?.let { event.droppedExp = it }
        }
        // The mob is gone after this event; its damage hooks would only be dead weight.
        damagedHooks.remove(uuid)
        attackHooks.remove(uuid)
        val hook = deathHooks.remove(uuid) ?: return
        runHook(hook.scriptName, "entity onDeath") { hook.callback(event.entity, event.entity.killer) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamaged(event: EntityDamageEvent) {
        if (damagedHooks.isEmpty()) return
        val entity = event.entity as? LivingEntity ?: return
        val hook = damagedHooks[entity.uniqueId] ?: return
        runHook(hook.scriptName, "entity onDamaged") { hook.callback(entity, event) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onAttack(event: EntityDamageByEntityEvent) {
        if (attackHooks.isEmpty()) return
        val attacker = event.damager as? LivingEntity ?: return
        val hook = attackHooks[attacker.uniqueId] ?: return
        runHook(hook.scriptName, "entity onAttack") { hook.callback(attacker, event) }
    }

    // Catches every removal cause (death, /kill, distance despawn, other plugins), not only script unload.
    // Without it the maps would grow forever on a server where mobs spawn and die naturally.
    // NOTE: never touch the entity itself (no .remove()) in here; Paper can throw
    // ConcurrentModificationException if removal happens while the world is iterating its entities.
    @EventHandler
    fun onRemove(event: EntityRemoveEvent) {
        if (hooksIdle() && entitiesByScript.isEmpty() && removeOnReload.isEmpty()) return
        val entity = event.entity as? LivingEntity ?: return
        // Saved with the chunk and coming back under the same UUID: keep the hooks and the index entry.
        if (event.cause == EntityRemoveEvent.Cause.UNLOAD && entity.isPersistent) return
        val uuid = entity.uniqueId
        // Hooks can exist for entities we never spawned, so this must not depend on the owner tag.
        dropHooks(uuid)
        removeOnReload.remove(uuid)
        ownerOf(entity)?.let { entitiesByScript[it]?.remove(uuid) }
    }

    // Saved entities outlive the in-memory index (restart, script unloaded while the chunk was unloaded).
    // An ephemeral one that nobody tracks has lost its script behavior; drop it instead of leaving an inert mob.
    @EventHandler
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (!plugin.isEnabled) return
        // Only living entities are ever spawned by scripts; reading the PDC of items and armor stands in every loaded chunk is wasted work.
        val orphans = event.entities.filter { entity ->
            if (entity !is LivingEntity) return@filter false
            val pdc = entity.persistentDataContainer
            val owner = pdc.get(ownerKey, PersistentDataType.STRING) ?: return@filter false
            pdc.has(ephemeralKey, PersistentDataType.BYTE) && entitiesByScript[owner]?.contains(entity.uniqueId) != true
        }
        if (orphans.isEmpty()) return
        // Removal is deferred: the entities are still being added to the world inside this event.
        Bukkit.getScheduler().runTask(plugin, Runnable { orphans.forEach { if (it.isValid) it.remove() } })
    }

    fun cleanupForScript(scriptName: String) {
        deathHooks.values.removeIf { it.scriptName == scriptName }
        damagedHooks.values.removeIf { it.scriptName == scriptName }
        attackHooks.values.removeIf { it.scriptName == scriptName }
        dropSpecs.values.removeIf { it.scriptName == scriptName }
        val ids = entitiesByScript.remove(scriptName) ?: return
        ids.forEach { uuid ->
            val shouldRemove = removeOnReload.remove(uuid) ?: true
            if (shouldRemove) {
                runCatching { Bukkit.getEntity(uuid)?.remove() }
                    .onFailure { plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_CLEANUP_FAILED.tag} Не удалось убрать сущность $uuid при выгрузке: ${it.message}") }
            }
        }
    }
}
