package ru.privateserver.ksl

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

class KSLGuiHolder(val gui: KSLGuiInstance) : InventoryHolder {
    private lateinit var inv: Inventory
    override fun getInventory(): Inventory = inv
    internal fun bind(inventory: Inventory) { inv = inventory }
}
