package ru.privateserver.ksl.std.player

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

private val miniMessage: MiniMessage = MiniMessage.miniMessage()

/** Sends plain text to the player. Must be called on the server thread. */
fun Player.tell(message: String) = sendMessage(message)

/** Sends MiniMessage-formatted text to the player. Must be called on the server thread. */
fun Player.tellRich(message: String) = sendMessage(miniMessage.deserialize(message))

/** Sends an Adventure component to the player. Must be called on the server thread. */
fun Player.tell(component: Component) = sendMessage(component)

/** Checks that the player has at least one non-blank permission node. */
fun Player.hasAnyPermission(vararg nodes: String): Boolean =
    nodes.filter(String::isNotBlank).any(::hasPermission)

/** Checks that the player has every supplied non-blank permission node. */
fun Player.hasAllPermissions(vararg nodes: String): Boolean =
    nodes.isNotEmpty() && nodes.all { it.isNotBlank() && hasPermission(it) }

/** Returns true if the player's inventory has no empty storage slot. */
fun Player.isInventoryFull(): Boolean = inventory.firstEmpty() == -1

/**
 * Adds an item to the player's inventory and returns items that did not fit.
 * Bukkit inventory access must happen on the server thread.
 */
fun Player.give(item: ItemStack): Map<Int, ItemStack> = inventory.addItem(item.clone())

/** Removes matching items from the player's storage inventory. */
fun Player.removeItems(matcher: (ItemStack) -> Boolean): Int {
    var removed = 0
    inventory.storageContents.forEachIndexed { index, stack ->
        if (stack != null && matcher(stack)) {
            removed += stack.amount
            inventory.setItem(index, null)
        }
    }
    return removed
}

/** Counts matching items in the player's storage inventory. */
fun Player.countItems(matcher: (ItemStack) -> Boolean): Int =
    inventory.storageContents.filterNotNull().filter(matcher).sumOf(ItemStack::getAmount)
