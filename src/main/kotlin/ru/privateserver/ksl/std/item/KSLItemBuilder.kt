package ru.privateserver.ksl.std.item

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import ru.privateserver.ksl.std.component.toMiniMessageComponent
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/** Mutable builder for concise custom item definitions in KSL scripts. */
class KSLItemBuilder(material: Material, amount: Int = 1) {
    private var stack: ItemStack

    init {
        require(amount > 0) { "amount must be positive" }
        require(!material.isAir) { "Use a non-air material for a custom item" }
        stack = ItemStack(material, amount)
    }

    fun name(value: String) {
        val component = value.toMiniMessageComponent()
            .decoration(TextDecoration.ITALIC, false)
        stack = stack.withDisplayName(component)
    }

    fun plainName(value: String) {
        val component = Component.text(value)
            .decoration(TextDecoration.ITALIC, false)
        stack = stack.withDisplayName(component)
    }

    fun lore(vararg lines: String) {
        val parsed = lines.map {
            it.toMiniMessageComponent()
                .decoration(TextDecoration.ITALIC, false)
        }
        stack = stack.withLore(parsed)
    }

    fun addLore(vararg lines: String) {
        val parsed = lines.map {
            it.toMiniMessageComponent()
                .decoration(TextDecoration.ITALIC, false)
        }
        stack = stack.withAddedLore(parsed)
    }

    fun unbreakable(enabled: Boolean = true) {
        stack = stack.withUnbreakable(enabled)
    }

    fun modelData(value: Int?) {
        stack = stack.withCustomModelData(value)
    }

    fun amount(value: Int) {
        stack = stack.copyWithAmount(value)
    }

    fun enchant(
        enchantment: Enchantment,
        level: Int = 1,
        unsafe: Boolean = false
    ) {
        stack = stack.withEnchantment(enchantment, level, unsafe)
    }

    fun customEnchant(enchantment: KSLCustomEnchantment, level: Int = 1) {
        stack = stack.withCustomEnchant(enchantment, level)
    }

    fun flags(vararg flags: ItemFlag) {
        stack = stack.withItemFlags(*flags)
    }

    fun hideAttributes() = flags(ItemFlag.HIDE_ATTRIBUTES)
    fun hideEnchants() = flags(ItemFlag.HIDE_ENCHANTS)
    fun hideUnbreakable() = flags(ItemFlag.HIDE_UNBREAKABLE)
    fun hideAll() = flags(*ItemFlag.values())

    fun loreComponents(vararg lines: Component) {
        stack = stack.withLore(lines.toList())
    }

    fun build(): ItemStack = stack.clone()
}
