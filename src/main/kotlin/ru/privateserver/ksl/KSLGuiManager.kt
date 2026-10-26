package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent

class KSLGuiManager(private val plugin: KotlinScriptLoaderPlugin) : Listener {

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? KSLGuiHolder ?: return
        holder.gui.handleClick(event)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? KSLGuiHolder ?: return
        val player = event.player as? Player ?: return
        holder.gui.handleClose(player)
    }

    fun closeGuisForScript(scriptName: String) {
        Bukkit.getOnlinePlayers().forEach { player ->
            val holder = player.openInventory.topInventory.holder as? KSLGuiHolder ?: return@forEach
            if (holder.gui.scriptName == scriptName) player.closeInventory()
        }
    }
}
