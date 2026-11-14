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
/**
 * Returns how many additional items similar to [item] this inventory can hold.
 * Existing stack limits and the inventory maximum are both respected.
 */
fun Inventory.spaceFor(item: ItemStack): Int {
    require(!item.type.isAir && item.amount > 0) { "item must not be empty" }
    val targetLimit = minOf(item.maxStackSize, maxStackSize)
    var capacity = 0
    for (slot in 0 until size) {
        val current = getItem(slot)
        val available = when {
            current == null || current.type.isAir || current.amount <= 0 -> targetLimit
            current.isSimilar(item) -> (minOf(targetLimit, current.maxStackSize) - current.amount).coerceAtLeast(0)
            else -> 0
        }
        capacity = Math.addExact(capacity, available)
    }
    return capacity
}

/** True when [amount] more items similar to [item] can fit without dropping anything. */
fun Inventory.canFit(item: ItemStack, amount: Int = item.amount): Boolean {
    require(amount >= 0) { "amount must not be negative" }
    require(!item.type.isAir && item.amount > 0) { "item must not be empty" }
    return amount <= spaceFor(item)
}

/** True when this inventory can accept at least one more item similar to [item]. */
fun Inventory.hasRoomFor(item: ItemStack): Boolean = canFit(item, 1)

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

/** Number of empty slots. Inventory access must happen on the server thread. */
fun Inventory.emptySlots(): Int =
    (0 until size).count { getItem(it).let { stack -> stack == null || stack.type.isAir || stack.amount <= 0 } }

/** True when there is no completely empty slot. Partial similar stacks may still have room. */
fun Inventory.isFull(): Boolean = firstEmpty() == -1

/** Returns the first slot whose non-empty item matches [predicate], or -1 when absent. */
fun Inventory.firstMatchingSlot(predicate: (ItemStack) -> Boolean): Int =
    (0 until size).firstOrNull { slot ->
        val stack = getItem(slot)
        stack != null && stack.amount > 0 && !stack.type.isAir && predicate(stack)
    } ?: -1

/** Counts non-empty stacks matching [predicate]. */
fun Inventory.countMatching(predicate: (ItemStack) -> Boolean): Int =
    (0 until size).count { slot ->
        val stack = getItem(slot)
        stack != null && stack.amount > 0 && !stack.type.isAir && predicate(stack)
    }

/** Returns all slot indices whose non-empty stack matches [predicate]. */
fun Inventory.slotsMatching(predicate: (ItemStack) -> Boolean): List<Int> =
    (0 until size).filter { slot ->
        val stack = getItem(slot)
        stack != null && stack.amount > 0 && predicate(stack)
    }

/**
 * Removes at most [amount] items from stacks matching [predicate].
 * Returns the number removed. Inventory access must happen on the server thread.
 */
fun Inventory.removeMatching(amount: Int, predicate: (ItemStack) -> Boolean): Int {
    require(amount >= 0) { "amount must not be negative" }
    var remaining = amount
    var removed = 0
    for (slot in 0 until size) {
        if (remaining == 0) break
        val stack = getItem(slot) ?: continue
        if (stack.amount <= 0 || !predicate(stack)) continue
        val take = minOf(stack.amount, remaining)
        val left = stack.amount - take
        if (left == 0) {
            setItem(slot, null)
        } else {
            setItem(slot, stack.clone().apply { this.amount = left })
        }
        removed += take
        remaining -= take
    }
    return removed
}

/**
 * Takes up to [amount] items from one slot and returns them as a new stack.
 * Returns null for an empty slot. The original inventory stack is not returned.
 */
fun Inventory.take(slot: Int, amount: Int): ItemStack? {
    require(slot in 0 until size) { "slot must be in 0 until $size" }
    require(amount >= 0) { "amount must not be negative" }
    if (amount == 0) return null
    val current = getItem(slot) ?: return null
    if (current.type.isAir || current.amount <= 0) return null
    val taken = minOf(current.amount, amount)
    val result = current.clone().apply { this.amount = taken }
    val left = current.amount - taken
    if (left == 0) setItem(slot, null)
    else setItem(slot, current.clone().apply { this.amount = left })
    return result
}

/**
 * Adds copies of all supplied stacks and returns copies of leftovers that did not fit.
 * The caller's input stacks are never mutated.
 */
fun Inventory.addAll(items: Iterable<ItemStack>): List<ItemStack> {
    val copies = items.filter { !it.type.isAir && it.amount > 0 }.map(ItemStack::clone)
    if (copies.isEmpty()) return emptyList()
    return addItem(*copies.toTypedArray()).values.map(ItemStack::clone)
}
