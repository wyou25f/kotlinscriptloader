package ru.privateserver.ksl

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack

class KSLEntityBuilder(private val entity: LivingEntity) {

    fun equip(template: String) {
        val attrs = KSLTemplateParser.parse(template)
        val eq = entity.equipment ?: return

        fun item(key: String): ItemStack? = attrs[key]?.let { Material.matchMaterial(it) }?.let { ItemStack(it) }

        item("HAND")?.let { eq.setItemInMainHand(it) }
        item("OFFHAND")?.let { eq.setItemInOffHand(it) }
        item("HELMET")?.let { eq.helmet = it }
        item("CHESTPLATE")?.let { eq.chestplate = it }
        item("LEGGINGS")?.let { eq.leggings = it }
        item("BOOTS")?.let { eq.boots = it }
    }
}
