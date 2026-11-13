package ru.privateserver.ksl.std.item

import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack

/**
 * Returns a cloned stack with [amount]. The original stack is not modified.
 * A zero amount is allowed to support empty-stack representations.
 */
fun ItemStack.copyWithAmount(amount: Int): ItemStack {
    require(amount >= 0) { "amount must not be negative" }
    return clone().apply { this.amount = amount }
}

/** True when this stack represents air or has no items. */
fun ItemStack.isEmptyStack(): Boolean = type.isAir || amount <= 0

/** Returns the Adventure display name, or null when no custom name is set. */
fun ItemStack.displayNameOrNull(): Component? =
    itemMeta?.takeIf { it.hasDisplayName() }?.displayName()

/** Returns a cloned stack with the supplied Adventure display name. */
fun ItemStack.withDisplayName(name: Component): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.displayName(name)
        itemMeta = meta
    }

/** Returns a cloned stack with a literal display name. */
fun ItemStack.withDisplayName(name: String): ItemStack =
    withDisplayName(Component.text(name))

/** Returns a cloned stack with the supplied Adventure lore lines. */
fun ItemStack.withLore(lines: List<Component>): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.lore(lines.toList())
        itemMeta = meta
    }

/** Returns a cloned stack with a plain-text lore list. */
fun ItemStack.withLore(vararg lines: String): ItemStack =
    withLore(lines.map(Component::text))

/** Returns a cloned stack with its unbreakable flag changed. */
fun ItemStack.withUnbreakable(unbreakable: Boolean = true): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.isUnbreakable = unbreakable
        itemMeta = meta
    }

/**
 * Returns a cloned stack with legacy custom model data.
 * Prefer item model / data component APIs for new resource-pack pipelines.
 */
@Suppress("DEPRECATION")
fun ItemStack.withCustomModelData(modelData: Int?): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.setCustomModelData(modelData)
        itemMeta = meta
    }

/** Returns a cloned stack with [enchantment] at [level]. */
fun ItemStack.withEnchantment(
    enchantment: org.bukkit.enchantments.Enchantment,
    level: Int = 1,
    ignoreLevelRestriction: Boolean = false
): ItemStack {
    require(level > 0) { "enchantment level must be positive" }
    return clone().apply {
        val meta = itemMeta ?: return@apply
        meta.addEnchant(enchantment, level, ignoreLevelRestriction)
        itemMeta = meta
    }
}

/** Returns a cloned stack without [enchantment]. */
fun ItemStack.withoutEnchantment(enchantment: org.bukkit.enchantments.Enchantment): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.removeEnchant(enchantment)
        itemMeta = meta
    }

/** Checks whether this stack carries [enchantment]. */
fun ItemStack.hasEnchantment(enchantment: org.bukkit.enchantments.Enchantment): Boolean =
    itemMeta?.hasEnchant(enchantment) == true
