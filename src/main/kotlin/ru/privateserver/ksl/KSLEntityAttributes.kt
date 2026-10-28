package ru.privateserver.ksl

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity

object KSLEntityAttributes {

    // Template keys that spawnCustomEntity handles itself.
    val coreKeys = setOf("TYPE", "NAME", "HP", "AI", "BABY")

    // Vanilla attribute keys lost their "generic." prefix in newer versions; accept both spellings.
    private fun find(name: String): Attribute? =
        Registry.ATTRIBUTE.get(NamespacedKey.minecraft(name))
            ?: Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.$name"))

    fun setBase(entity: LivingEntity, name: String, value: Double): Boolean {
        val instance = find(name)?.let { entity.getAttribute(it) } ?: return false
        instance.baseValue = value
        return true
    }

    fun getBase(entity: LivingEntity, name: String): Double? =
        find(name)?.let { entity.getAttribute(it) }?.baseValue

    fun multiplyBase(entity: LivingEntity, name: String, factor: Double): Boolean {
        val instance = find(name)?.let { entity.getAttribute(it) } ?: return false
        instance.baseValue = instance.baseValue * factor
        return true
    }

    private fun number(value: String): Double =
        value.toDoubleOrNull() ?: error("ожидалось число, получено '$value'")

    private fun flag(value: String): Boolean =
        value.toBooleanStrictOrNull() ?: error("ожидалось true или false, получено '$value'")

    private fun ensure(ok: Boolean, entity: LivingEntity, name: String) {
        if (!ok) error("у ${entity.type} нет атрибута $name")
    }

    fun registerDefaults(registry: KSLOwnedRegistry<(LivingEntity, String) -> Unit>) {
        // Multiplier of the mob's own base speed: SPEED:1.5 is 50% faster.
        registry.put("SPEED", { e, v -> ensure(multiplyBase(e, "movement_speed", number(v)), e, "movement_speed") }, null)
        registry.put("DAMAGE", { e, v -> ensure(setBase(e, "attack_damage", number(v)), e, "attack_damage") }, null)
        registry.put("ARMOR", { e, v -> ensure(setBase(e, "armor", number(v)), e, "armor") }, null)
        registry.put("KNOCKBACK_RESIST", { e, v -> ensure(setBase(e, "knockback_resistance", number(v)), e, "knockback_resistance") }, null)
        registry.put("GLOW", { e, v -> e.isGlowing = flag(v) }, null)
        registry.put("SILENT", { e, v -> e.isSilent = flag(v) }, null)
        registry.put("INVULNERABLE", { e, v -> e.isInvulnerable = flag(v) }, null)
    }
}
