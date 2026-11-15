package ru.privateserver.ksl

import org.bukkit.scheduler.BukkitTask
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.entity.Entity
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
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
    private val tickTasks = ConcurrentHashMap<UUID, MutableSet<Hook<BukkitTask>>>()
    private val unloadingEntityIds = ConcurrentHashMap.newKeySet<UUID>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        registerEntityRemovalEvent()
        // Chunks already loaded before this plugin's onEnable do not emit ChunkLoadEvent again.
        // Scan them one tick later so orphan cleanup also covers entities restored at server startup.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!plugin.isEnabled) return@Runnable
            Bukkit.getWorlds().forEach { world ->
                world.loadedChunks.forEach { chunk -> removeOrphanedEntities(chunk.entities.asList()) }
            }
        })
    }

    /**
     * Paper renamed its removal event over time. Register whichever event exists
     * without linking either class into the plugin's constant pool.
     */
    private fun registerEntityRemovalEvent() {
        val eventNames = listOf(
            "org.bukkit.event.entity.EntityRemoveEvent",
            "com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent"
        )
        val eventClass = eventNames.firstNotNullOfOrNull { name ->
            try {
                Class.forName(name, false, Bukkit::class.java.classLoader)
                    .takeIf { Event::class.java.isAssignableFrom(it) }
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: LinkageError) {
                null
            } catch (_: SecurityException) {
                null
            }
        } ?: run {
            plugin.logger.warning("Paper entity-removal event is unavailable; non-death entity cleanup will use script unload and chunk lifecycle fallbacks")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val typedEventClass = eventClass as Class<out Event>
        Bukkit.getPluginManager().registerEvent(
            typedEventClass,
            this,
            EventPriority.MONITOR,
            EventExecutor { _, event -> handleEntityRemoval(event) },
            plugin,
            true
        )
    }

    fun scriptNames(): Set<String> = entitiesByScript.keys +
        deathHooks.values.map { it.scriptName } +
        damagedHooks.values.map { it.scriptName } +
        attackHooks.values.map { it.scriptName } +
        dropSpecs.values.map { it.scriptName } +
        tickTasks.values.flatMap { tasks -> tasks.map { it.scriptName } }

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
        entitiesByScript.computeIfPresent(scriptName) { _, ids ->
            ids.remove(uuid)
            if (ids.isEmpty()) null else ids
        }
        removeOnReload.remove(uuid)
        dropHooks(uuid)
    }

    private fun dropHooks(uuid: UUID) {
        deathHooks.remove(uuid)
        damagedHooks.remove(uuid)
        attackHooks.remove(uuid)
        dropSpecs.remove(uuid)
    }

    // PDC data can be changed by other plugins. Never leave a UUID indexed forever
    // merely because its owner tag disappeared before the entity was removed.
    private fun untrackEntity(uuid: UUID, owner: String?) {
        var removedFromOwner = false
        if (owner != null) {
            entitiesByScript.computeIfPresent(owner) { _, ids ->
                removedFromOwner = ids.remove(uuid)
                if (ids.isEmpty()) null else ids
            }
        }
        if (removedFromOwner) return

        // The tag may have been overwritten, so fall back to the index itself.
        entitiesByScript.entries.removeIf { entry ->
            entry.value.remove(uuid)
            entry.value.isEmpty()
        }
    }

    fun trackTickTask(scriptName: String, entityId: UUID, task: BukkitTask) {
        tickTasks.computeIfAbsent(entityId) { ConcurrentHashMap.newKeySet() }.add(Hook(scriptName, task))
    }

    fun untrackTickTask(scriptName: String, entityId: UUID, taskId: Int) {
        tickTasks.computeIfPresent(entityId) { _, tasks ->
            tasks.removeIf { it.scriptName == scriptName && it.callback.taskId == taskId }
            if (tasks.isEmpty()) null else tasks
        }
    }

    private fun cancelTickTasks(entityId: UUID) {
        tickTasks.remove(entityId)?.forEach { tracked ->
            tracked.callback.cancel()
            plugin.untrackTask(tracked.scriptName, tracked.callback.taskId)
        }
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
        deathHooks.isEmpty() && damagedHooks.isEmpty() && attackHooks.isEmpty() && dropSpecs.isEmpty() && tickTasks.isEmpty()

    @EventHandler
    fun onDeath(event: EntityDeathEvent) {
        val uuid = event.entity.uniqueId
        val owner = ownerOf(event.entity)
        val tracked = (owner != null && entitiesByScript[owner]?.contains(uuid) == true) ||
            removeOnReload.containsKey(uuid)
        if (hooksIdle() && !tracked) return
        dropSpecs.remove(uuid)?.let { spec ->
            if (!spec.keepDefault) event.drops.clear()
            event.drops.addAll(spec.items.map { it.clone() })
            spec.exp?.let { event.droppedExp = it }
        }
        // The mob is gone after this event; its damage hooks would only be dead weight.
        damagedHooks.remove(uuid)
        attackHooks.remove(uuid)
        val hook = deathHooks.remove(uuid)
        // Death is terminal for a scripted living entity. Release its in-memory ownership
        // immediately instead of relying on a later EntityRemoveEvent ordering.
        removeOnReload.remove(uuid)
        cancelTickTasks(uuid)
        untrackEntity(uuid, owner)
        hook?.let { runHook(it.scriptName, "entity onDeath") { it.callback(event.entity, event.entity.killer) } }
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

    // Entity removal event APIs differ between Paper versions. Chunk events provide
    // the unload distinction needed by the older EntityRemoveFromWorldEvent.
    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        event.chunk.entities.forEach { entity ->
            if (entity is LivingEntity) unloadingEntityIds.add(entity.uniqueId)
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        val entities = event.chunk.entities.asList()
        entities.forEach { unloadingEntityIds.remove(it.uniqueId) }
        // Entity data is fully attached to the world after the chunk event completes.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (plugin.isEnabled) removeOrphanedEntities(entities)
        })
    }

    private fun handleEntityRemoval(event: Event) {
        if (hooksIdle() && entitiesByScript.isEmpty() && removeOnReload.isEmpty()) return
        val entity = runCatching {
            event.javaClass.getMethod("getEntity").invoke(event) as? LivingEntity
        }.getOrNull() ?: return

        // Newer Paper exposes the cause directly; older Paper uses the world-removal event.
        val cause = runCatching { event.javaClass.getMethod("getCause").invoke(event) }.getOrNull()
        if ((cause as? Enum<*>)?.name.equals("UNLOAD", ignoreCase = true)) return

        val uuid = entity.uniqueId
        // EntityRemoveFromWorldEvent is also fired for chunk unloads on older Paper.
        if (unloadingEntityIds.remove(uuid)) return

        dropHooks(uuid)
        removeOnReload.remove(uuid)
        cancelTickTasks(uuid)
        untrackEntity(uuid, ownerOf(entity))
    }

    // Ephemeral entities left in saved chunks after their owning script disappeared
    // have lost their callbacks, so remove them once they're safely in the world.
    private fun removeOrphanedEntities(entities: Iterable<Entity>) {
        if (!plugin.isEnabled) return
        val orphans = entities.filterIsInstance<LivingEntity>().filter { entity ->
            val pdc = entity.persistentDataContainer
            val owner = pdc.get(ownerKey, PersistentDataType.STRING) ?: return@filter false
            pdc.has(ephemeralKey, PersistentDataType.BYTE) &&
                entitiesByScript[owner]?.contains(entity.uniqueId) != true
        }
        if (orphans.isEmpty()) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            orphans.forEach { if (it.isValid) it.remove() }
        })
    }

    fun cleanupForScript(scriptName: String) {
        tickTasks.entries.toList().forEach { (uuid, tasks) ->
            tasks.filter { it.scriptName == scriptName }.forEach { tracked ->
                tracked.callback.cancel()
                plugin.untrackTask(scriptName, tracked.callback.taskId)
                untrackTickTask(scriptName, uuid, tracked.callback.taskId)
            }
        }
        deathHooks.values.removeIf { it.scriptName == scriptName }
        damagedHooks.values.removeIf { it.scriptName == scriptName }
        attackHooks.values.removeIf { it.scriptName == scriptName }
        dropSpecs.values.removeIf { it.scriptName == scriptName }
        val ids = entitiesByScript.remove(scriptName) ?: return
        ids.forEach { uuid ->
            cancelTickTasks(uuid)
            val shouldRemove = removeOnReload.remove(uuid) ?: true
            if (shouldRemove) {
                runCatching { Bukkit.getEntity(uuid)?.remove() }
                    .onFailure { plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_CLEANUP_FAILED.tag} Не удалось убрать сущность $uuid при выгрузке: ${it.message}") }
            }
        }
    }
}
