package ru.privateserver.ksl.std.inventory

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * Returns the first slot containing an item similar to [item], or -1 when absent.
 * Inventory access must happen on the server thread.
 */
fun Inventory.firstSimilarSlot(item: ItemStack): Int {
    for (slot in 0 until size) {
        val stack = getItem(slot) ?: continue
        if (stack.amount > 0 && stack.isSimilar(item)) return slot
    }
    return -1
}

/**
 * Counts all items similar to [item] across this inventory's slots.
 * Inventory access must happen on the server thread.
 */
fun Inventory.countSimilar(item: ItemStack): Int {
    var total = 0
    for (slot in 0 until size) {
        val stack = getItem(slot) ?: continue
        if (stack.amount > 0 && stack.isSimilar(item)) {
            total = Math.addExact(total, stack.amount)
        }
    }
    return total
}

/**
 * Removes up to [amount] items similar to [item] and returns the number removed.
 * The supplied [item] is never mutated. Inventory access must happen on the server thread.
 */
fun Inventory.removeSimilar(item: ItemStack, amount: Int): Int {
    require(amount >= 0) { "amount must not be negative" }
    if (amount == 0) return 0

    var remaining = amount
    var removed = 0
    for (slot in 0 until size) {
        if (remaining == 0) break
        val current = getItem(slot) ?: continue
        if (current.amount <= 0 || !current.isSimilar(item)) continue

        val take = minOf(current.amount, remaining)
        val left = current.amount - take
        if (left == 0) {
            setItem(slot, null)
        } else {
            setItem(slot, current.clone().apply { this.amount = left })
        }
        removed += take
        remaining -= take
    }
    return removed
}
