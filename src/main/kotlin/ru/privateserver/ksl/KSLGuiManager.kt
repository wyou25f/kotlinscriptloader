package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent

class KSLGuiManager(private val plugin: KotlinScriptLoaderPlugin) : Listener {

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
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
        Bukkit.getOnlinePlayers().forEach { player ->
            val holder = player.openInventory.topInventory.getHolder(false) as? KSLGuiHolder ?: return@forEach
            if (holder.gui.scriptName == scriptName) {
                holder.gui.dispose()
                player.closeInventory()
            }
        }
    }
}
