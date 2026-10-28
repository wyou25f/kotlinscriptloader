package ru.privateserver.ksl

import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class KSLEntityBuilder(
    private val entity: LivingEntity,
    private val plugin: KotlinScriptLoaderPlugin,
    private val scriptName: String
) {

    private val equipKeys = setOf("HAND", "OFFHAND", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")

    // dropChance: 0f means the gear never drops; null keeps the vanilla chance (about 8.5% per piece).
    fun equip(template: String, dropChance: Float? = null) {
        val attrs = KSLTemplateParser.parse(template)
        val eq = entity.equipment ?: return

        attrs.keys.filter { it !in equipKeys }.forEach {
            plugin.logger.warning("[$scriptName] equip: неизвестный слот $it (доступны: ${equipKeys.joinToString(", ")})")
        }

        fun item(key: String): ItemStack? {
            val raw = attrs[key] ?: return null
            val material = Material.matchMaterial(raw)
            if (material == null || !material.isItem) {
                plugin.logger.warning("[$scriptName] equip: '$raw' для $key — не предмет")
                return null
            }
            return ItemStack(material)
        }

        item("HAND")?.let { eq.setItemInMainHand(it) }
        item("OFFHAND")?.let { eq.setItemInOffHand(it) }
        item("HELMET")?.let { eq.helmet = it }
        item("CHESTPLATE")?.let { eq.chestplate = it }
        item("LEGGINGS")?.let { eq.leggings = it }
        item("BOOTS")?.let { eq.boots = it }

        dropChance?.let { chance ->
            eq.itemInMainHandDropChance = chance
            eq.itemInOffHandDropChance = chance
            eq.helmetDropChance = chance
            eq.chestplateDropChance = chance
            eq.leggingsDropChance = chance
            eq.bootsDropChance = chance
        }
    }

    // Base value by vanilla attribute name: attribute("attack_damage", 8.0).
    fun attribute(name: String, value: Double) {
        require(KSLEntityAttributes.setBase(entity, name, value)) { "у ${entity.type} нет атрибута '$name'" }
    }

    // Multiplier of the mob's own speed: speed(1.5) is 50% faster.
    fun speed(multiplier: Double) {
        require(KSLEntityAttributes.multiplyBase(entity, "movement_speed", multiplier)) { "у ${entity.type} нет атрибута movement_speed" }
    }

    fun damage(value: Double) = attribute("attack_damage", value)

    fun armor(value: Double) = attribute("armor", value)

    // seconds < 0 means until the mob dies.
    fun potion(type: PotionEffectType, seconds: Int = -1, amplifier: Int = 0) {
        val ticks = if (seconds < 0) PotionEffect.INFINITE_DURATION else seconds * 20
        entity.addPotionEffect(PotionEffect(type, ticks, amplifier))
    }

    fun glow(on: Boolean = true) { entity.isGlowing = on }

    fun silent(on: Boolean = true) { entity.isSilent = on }

    fun invulnerable(on: Boolean = true) { entity.isInvulnerable = on }

    // Replaces the vanilla drops (keepDefault = true adds to them); exp overrides the dropped experience.
    fun drops(vararg items: ItemStack, exp: Int? = null, keepDefault: Boolean = false) {
        plugin.entityManager.setDrops(scriptName, entity, items.toList(), exp, keepDefault)
    }
}
