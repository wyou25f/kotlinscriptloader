package ru.privateserver.ksl.std.player

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.privateserver.ksl.std.inventory.countSimilar
import ru.privateserver.ksl.std.inventory.removeSimilar
import ru.privateserver.ksl.std.item.isEmptyStack

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
fun Player.give(item: ItemStack): Map<Int, ItemStack> {
    require(!item.isEmptyStack()) { "item must not be empty" }
    return inventory.addItem(item.clone()).mapValues { (_, stack) -> stack.clone() }
}

/** Checks whether [amount] more items can fit in the player's storage inventory. */
fun Player.canFit(item: ItemStack, amount: Int = item.amount): Boolean {
    require(amount >= 0) { "amount must not be negative" }
    require(!item.isEmptyStack()) { "item must not be empty" }
    val contents = inventory.storageContents
    val targetLimit = minOf(item.maxStackSize, inventory.maxStackSize)
    var capacity = 0
    contents.forEach { current ->
        val available = when {
            current == null || current.isEmptyStack() -> targetLimit
            current.isSimilar(item) -> (minOf(targetLimit, current.maxStackSize) - current.amount).coerceAtLeast(0)
            else -> 0
        }
        capacity = Math.addExact(capacity, available)
    }
    return amount <= capacity
}

/**
 * Adds a copy to storage and naturally drops any remainder at the player's location.
 * Returns the number of items dropped on the ground.
 */
fun Player.giveOrDrop(item: ItemStack): Int {
    require(!item.isEmptyStack()) { "item must not be empty" }
    val leftovers = inventory.addItem(item.clone()).values
    var dropped = 0
    leftovers.forEach { stack ->
        dropped = Math.addExact(dropped, stack.amount)
        world.dropItemNaturally(location, stack.clone())
    }
    return dropped
}

/** Removes matching items from the player's storage inventory. */
fun Player.removeItems(matcher: (ItemStack) -> Boolean): Int {
    var removed = 0
    inventory.storageContents.forEachIndexed { index, stack ->
        if (stack != null && !stack.isEmptyStack() && matcher(stack)) {
            removed = Math.addExact(removed, stack.amount)
            inventory.setItem(index, null)
        }
    }
    return removed
}

/** Counts matching items in the player's storage inventory. */
fun Player.countItems(matcher: (ItemStack) -> Boolean): Int {
    var total = 0
    inventory.storageContents.filterNotNull().forEach { stack ->
        if (!stack.isEmptyStack() && matcher(stack)) total = Math.addExact(total, stack.amount)
    }
    return total
}

/** Removes at most [amount] matching items from storage slots only. */
fun Player.removeItems(amount: Int, matcher: (ItemStack) -> Boolean): Int {
    require(amount >= 0) { "amount must not be negative" }
    var remaining = amount
    var removed = 0
    inventory.storageContents.forEachIndexed { index, stack ->
        if (remaining == 0) return@forEachIndexed
        if (stack == null || stack.isEmptyStack() || !matcher(stack)) return@forEachIndexed
        val taken = minOf(stack.amount, remaining)
        val left = stack.amount - taken
        inventory.setItem(index, if (left == 0) null else stack.clone().apply { this.amount = left })
        removed = Math.addExact(removed, taken)
        remaining -= taken
    }
    return removed
}

/** Restores food and saturation, clamping both to Bukkit's supported ranges. */
fun Player.feed(food: Int = 20, saturation: Float = 5f) {
    require(saturation.isFinite()) { "saturation must be finite" }
    foodLevel = food.coerceIn(0, 20)
    this.saturation = saturation.coerceIn(0f, foodLevel.toFloat())
}

/** Heals by [amount] health points, never exceeding the player's current maximum health. */
fun Player.healBy(amount: Double): Double {
    require(amount.isFinite() && amount >= 0.0) { "amount must be finite and non-negative" }
    val maximum = getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: health
    val before = health
    health = (health + amount).coerceAtMost(maximum)
    return health - before
}

/** Removes up to [amount] items similar to [item] and returns the actual number removed. */
fun Player.remove(item: ItemStack, amount: Int): Int =
    inventory.removeSimilar(item, amount)

/** Counts items similar to [item] in the player's storage inventory. */
fun Player.count(item: ItemStack): Int =
    inventory.countSimilar(item)

/** Copies all non-empty storage contents, then clears those storage slots. Armor and offhand are preserved. */
fun Player.clearStorage(): List<ItemStack> {
    val removed = inventory.storageContents.filterNotNull().filterNot(ItemStack::isEmptyStack).map(ItemStack::clone)
    inventory.storageContents = arrayOfNulls(inventory.storageContents.size)
    return removed
}

/** Checks the player's current world by its exact world name. */
fun Player.isInWorld(worldName: String): Boolean =
    world.name.equals(worldName, ignoreCase = true)
