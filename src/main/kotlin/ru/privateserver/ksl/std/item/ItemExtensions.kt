package ru.privateserver.ksl.std.item

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.meta.ItemMeta
import java.lang.reflect.InvocationTargetException
import org.bukkit.enchantments.Enchantment


private val kslLegacyComponentSerializer: LegacyComponentSerializer by lazy(LazyThreadSafetyMode.PUBLICATION) {
    LegacyComponentSerializer.legacySection()
}

/**
 * Calls the Paper Adventure ItemMeta method when the implementation supplies it.
 * Older MockBukkit builds expose the interface method but do not implement it;
 * in that case callers fall back to Bukkit's long-standing string API.
 */
private fun invokeAdventureMeta(
    meta: ItemMeta,
    methodName: String,
    parameterTypes: Array<Class<*>>,
    vararg arguments: Any?
): Pair<Boolean, Any?> {
    val method = runCatching {
        ItemMeta::class.java.getMethod(methodName, *parameterTypes)
    }.getOrNull() ?: return false to null

    return try {
        true to method.invoke(meta, *arguments)
    } catch (failure: InvocationTargetException) {
        val cause = failure.targetException
        if (cause is AbstractMethodError || cause is NoSuchMethodError) {
            false to null
        } else {
            throw cause
        }
    } catch (_: AbstractMethodError) {
        false to null
    } catch (_: NoSuchMethodError) {
        false to null
    }
}

internal fun ItemMeta.kslDisplayName(): Component? {
    val (called, value) = invokeAdventureMeta(this, "displayName", emptyArray())
    if (called) return value as? Component

    @Suppress("DEPRECATION")
    return if (hasDisplayName()) kslLegacyComponentSerializer.deserialize(getDisplayName()) else null
}

internal fun ItemMeta.setKslDisplayName(name: Component) {
    val (called, _) = invokeAdventureMeta(this, "displayName", arrayOf(Component::class.java), name)
    if (called) return

    @Suppress("DEPRECATION")
    setDisplayName(kslLegacyComponentSerializer.serialize(name))
}

internal fun ItemMeta.kslLore(): List<Component> {
    val (called, value) = invokeAdventureMeta(this, "lore", emptyArray())
    if (called) {
        return (value as? List<*>)?.mapNotNull { it as? Component }.orEmpty()
    }

    @Suppress("DEPRECATION")
    val legacyLore = runCatching { getLore() }.getOrNull().orEmpty()
    return legacyLore.map(kslLegacyComponentSerializer::deserialize)
}

internal fun ItemMeta.setKslLore(lines: List<Component>?) {
    val (called, _) = invokeAdventureMeta(this, "lore", arrayOf(List::class.java), lines)
    if (called) return

    @Suppress("DEPRECATION")
    setLore(lines?.map(kslLegacyComponentSerializer::serialize))
}

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
    itemMeta?.takeIf { it.hasDisplayName() }?.kslDisplayName()

/** Returns a cloned stack with the supplied Adventure display name. */
fun ItemStack.withDisplayName(name: Component): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.setKslDisplayName(name)
        itemMeta = meta
    }

/** Returns a cloned stack with a literal display name. */
fun ItemStack.withDisplayName(name: String): ItemStack =
    withDisplayName(Component.text(name))

/** Returns a cloned stack with the supplied Adventure lore lines. */
fun ItemStack.withLore(lines: List<Component>): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.setKslLore(lines.toList())
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


/** Short alias for copyWithAmount; the original stack remains unchanged. */
fun ItemStack.amount(value: Int): ItemStack = copyWithAmount(value)

/** Short alias for withDisplayName. */
fun ItemStack.withName(name: String): ItemStack = withDisplayName(name)

/** Short alias for withDisplayName. */
fun ItemStack.named(name: String): ItemStack = withDisplayName(name)

/** Appends plain-text lore lines while preserving the existing lore. */
fun ItemStack.withAddedLore(vararg lines: String): ItemStack =
    withAddedLore(lines.map(Component::text))

/** Appends Adventure lore lines while preserving the existing lore. */
fun ItemStack.withAddedLore(lines: List<Component>): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.setKslLore(meta.kslLore() + lines)
        itemMeta = meta
    }

/** Removes all lore from a cloned stack. */
fun ItemStack.withoutLore(): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.setKslLore(null)
        itemMeta = meta
    }

/** Adds or replaces several vanilla enchantments on a cloned stack. */
fun ItemStack.withEnchantments(
    enchantments: Map<Enchantment, Int>,
    ignoreLevelRestriction: Boolean = false
): ItemStack {
    require(enchantments.values.all { it > 0 }) { "enchantment levels must be positive" }
    return clone().apply {
        val meta = itemMeta ?: return@apply
        enchantments.forEach { (enchantment, level) ->
            meta.addEnchant(enchantment, level, ignoreLevelRestriction)
        }
        itemMeta = meta
    }
}

/** Short alias for withEnchantment. */
fun ItemStack.enchant(
    enchantment: Enchantment,
    level: Int = 1,
    unsafe: Boolean = false
): ItemStack = withEnchantment(enchantment, level, unsafe)

/** Short alias for withoutEnchantment. */
fun ItemStack.unenchant(enchantment: Enchantment): ItemStack =
    withoutEnchantment(enchantment)

/** Returns the current level of enchantment, or zero when absent. */
fun ItemStack.enchantmentLevel(enchantment: Enchantment): Int =
    itemMeta?.getEnchantLevel(enchantment) ?: 0

/** Returns the vanilla enchantments currently present on this stack. */
fun ItemStack.enchantments(): Map<Enchantment, Int> =
    itemMeta?.enchants?.toMap().orEmpty()

/** Removes all vanilla enchantments from a cloned stack. */
fun ItemStack.withoutEnchantments(): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.enchants.keys.toList().forEach { meta.removeEnchant(it) }
        itemMeta = meta
    }

/** Adds item flags to a cloned stack. */
fun ItemStack.withItemFlags(vararg flags: ItemFlag): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.addItemFlags(*flags)
        itemMeta = meta
    }

/** Removes item flags from a cloned stack. */
fun ItemStack.withoutItemFlags(vararg flags: ItemFlag): ItemStack =
    clone().apply {
        val meta = itemMeta ?: return@apply
        meta.removeItemFlags(*flags)
        itemMeta = meta
    }

/** Short alias for withItemFlags. */
fun ItemStack.withFlags(vararg flags: ItemFlag): ItemStack =
    withItemFlags(*flags)

/** Short alias for withoutItemFlags. */
fun ItemStack.withoutFlags(vararg flags: ItemFlag): ItemStack =
    withoutItemFlags(*flags)

/** True when this stack's material matches one of materials. */
fun ItemStack.isOf(vararg materials: org.bukkit.Material): Boolean =
    type in materials

/** Alias for Bukkit's similarity check. */
fun ItemStack.isSimilarTo(other: ItemStack): Boolean =
    isSimilar(other)
