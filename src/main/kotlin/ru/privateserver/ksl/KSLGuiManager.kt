package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import java.util.WeakHashMap

class KSLGuiManager(private val plugin: KotlinScriptLoaderPlugin) : Listener {

    // Weak keys prevent the manager from retaining every short-lived GUI a script ever creates.
    private val trackedGuis = WeakHashMap<KSLGuiInstance, Boolean>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun track(gui: KSLGuiInstance) {
        synchronized(trackedGuis) { trackedGuis[gui] = true }
    }

    /** Disposes exactly one GUI; closing an old generation must not affect its replacement's GUIs. */
    fun dispose(gui: KSLGuiInstance) {
        if (!Bukkit.isPrimaryThread()) {
            val scheduled = runCatching {
                Bukkit.getScheduler().runTask(plugin, Runnable { dispose(gui) })
            }.isSuccess
            if (scheduled) return

            // Scheduler shutdown can race with plugin disable. Disable handlers immediately
            // even when Bukkit can no longer safely close the inventory from this thread.
            gui.dispose()
            synchronized(trackedGuis) { trackedGuis.remove(gui) }
            plugin.logger.warning("[GUI] Не удалось запланировать закрытие inventory для поколения скрипта '${gui.scriptName}'.")
            return
        }

        gui.dispose()
        Bukkit.getOnlinePlayers().forEach { player ->
            val holder = player.openInventory.topInventory.getHolder(false) as? KSLGuiHolder
            if (holder?.gui === gui) player.closeInventory()
        }
        synchronized(trackedGuis) { trackedGuis.remove(gui) }
    }

    // getHolder(false): the default variant snapshots block state on every click in any container, server-wide.
    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.getHolder(false) as? KSLGuiHolder ?: return
        holder.gui.handleClick(event)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.inventory.getHolder(false) as? KSLGuiHolder ?: return
        holder.gui.handleDrag(event)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.getHolder(false) as? KSLGuiHolder ?: return
        val player = event.player as? Player ?: return
        holder.gui.handleClose(player)
    }

    fun closeGuisForScript(scriptName: String) {
        val owned = synchronized(trackedGuis) {
            trackedGuis.keys.filter { it.scriptName == scriptName }
        }
        if (owned.isEmpty()) return

        // Dispose all still-referenced GUIs, not only the one currently visible to a player.
        owned.forEach { it.dispose() }
        Bukkit.getOnlinePlayers().forEach { player ->
            val holder = player.openInventory.topInventory.getHolder(false) as? KSLGuiHolder ?: return@forEach
            if (holder.gui.scriptName == scriptName) player.closeInventory()
        }
        synchronized(trackedGuis) { owned.forEach(trackedGuis::remove) }
    }
}
