package ru.privateserver.ksl

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Ageable
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import net.kyori.adventure.text.Component

class KSLEntityBuilder(
    private val entity: LivingEntity,
    private val plugin: KotlinScriptLoaderPlugin,
    private val scriptName: String
) {

    private val equipKeys = setOf("HAND", "OFFHAND", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")

    // dropChance: 0f means the gear never drops; null keeps the vanilla chance (about 8.5% per piece).
    fun equip(template: String, dropChance: Float? = null) {
        dropChance?.let { chance ->
            require(chance.isFinite() && chance in 0.0f..1.0f) { "dropChance должен быть конечным числом от 0.0 до 1.0" }
        }
        val attrs = KSLTemplateParser.parse(template)
        val eq = entity.equipment ?: return

        attrs.keys.filter { it !in equipKeys }.forEach {
            plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_BAD_EQUIP_SLOT.tag} equip: неизвестный слот $it (доступны: ${equipKeys.joinToString(", ")})")
        }

        fun item(key: String): ItemStack? {
            val raw = attrs[key] ?: return null
            val material = Material.matchMaterial(raw)
            if (material == null || !material.isItem) {
                plugin.logger.warning("[$scriptName] ${KSLError.ENTITY_BAD_EQUIP_ITEM.tag} equip: '$raw' для $key — не предмет")
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

    fun name(text: String, visible: Boolean = true) {
        entity.customName(MiniMessage.miniMessage().deserialize(text))
        entity.isCustomNameVisible = visible
    }

    fun name(component: Component, visible: Boolean = true) {
        entity.customName(component)
        entity.isCustomNameVisible = visible
    }

    fun customName(text: String, visible: Boolean = true) = name(text, visible)

    fun ai(on: Boolean = true) {
        entity.setAI(on)
    }

    fun baby(on: Boolean = true) {
        val ageable = entity as? Ageable
            ?: throw IllegalStateException(entity.type.toString() + " не поддерживает возраст")
        if (on) ageable.setBaby() else ageable.setAdult()
    }

    fun health(value: Double) {
        require(value.isFinite() && value >= 0.0 && value <= entity.getMaxHealth()) {
            "Здоровье должно находиться в диапазоне 0..максимальное здоровье"
        }
        entity.health = value
    }

    fun maxHealth(value: Double, healToFull: Boolean = true) {
        require(value.isFinite() && value > 0.0 && value <= 1024.0) {
            "Максимальное здоровье должно находиться в диапазоне (0, 1024]"
        }
        require(KSLEntityAttributes.setBase(entity, "max_health", value)) {
            "У " + entity.type + " нет атрибута max_health"
        }
        entity.health = if (healToFull) value else entity.health.coerceAtMost(value)
    }

    fun heal(amount: Double = entity.getMaxHealth()) {
        require(amount.isFinite() && amount >= 0.0) { "Количество лечения должно быть конечным неотрицательным числом" }
        entity.health = (entity.health + amount).coerceAtMost(entity.getMaxHealth())
    }

    fun target(target: LivingEntity?) {
        if (target != null) {
            require(target.isValid && !target.isDead) { "Цель должна быть живой и валидной сущностью" }
            require(target.world == entity.world) { "Цель должна находиться в том же мире, что и моб" }
        }
        requireMob().target = target
    }

    fun clearTarget() = target(null)

    fun persistent(on: Boolean = true) {
        entity.isPersistent = on
    }

    fun removeWhenFarAway(on: Boolean = true) {
        entity.setRemoveWhenFarAway(on)
    }

    fun gravity(on: Boolean = true) {
        entity.setGravity(on)
    }

    fun collidable(on: Boolean = true) {
        entity.isCollidable = on
    }

    fun canPickupItems(on: Boolean = true) {
        entity.setCanPickupItems(on)
    }

    fun fire(seconds: Int) {
        require(seconds >= 0 && seconds <= Int.MAX_VALUE / 20) { "Время горения должно быть неотрицательным и не переполнять ticks" }
        entity.fireTicks = seconds * 20
    }

    fun velocity(x: Double, y: Double, z: Double) {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Скорость моба должна состоять из конечных чисел" }
        entity.velocity = Vector(x, y, z)
    }

    fun teleport(location: Location): Boolean {
        require(location.world != null) { "Целевая локация должна принадлежать загруженному миру" }
        require(location.x.isFinite() && location.y.isFinite() && location.z.isFinite()) {
            "Координаты телепортации должны быть конечными числами"
        }
        return entity.teleport(location)
    }

    fun lookAt(target: LivingEntity) = lookAt(target.location)

    fun mainHand(item: ItemStack, dropChance: Float? = null) {
        val equipment = entity.equipment ?: return
        equipment.setItemInMainHand(item.clone())
        dropChance?.let {
            require(it.isFinite() && it in 0.0f..1.0f) { "Шанс выпадения должен быть от 0 до 1" }
            equipment.itemInMainHandDropChance = it
        }
    }

    fun offHand(item: ItemStack, dropChance: Float? = null) {
        val equipment = entity.equipment ?: return
        equipment.setItemInOffHand(item.clone())
        dropChance?.let {
            require(it.isFinite() && it in 0.0f..1.0f) { "Шанс выпадения должен быть от 0 до 1" }
            equipment.itemInOffHandDropChance = it
        }
    }

    fun helmet(item: ItemStack, dropChance: Float? = null) {
        val equipment = entity.equipment ?: return
        equipment.helmet = item.clone()
        dropChance?.let {
            require(it.isFinite() && it in 0.0f..1.0f) { "Шанс выпадения должен быть от 0 до 1" }
            equipment.helmetDropChance = it
        }
    }

    fun chestplate(item: ItemStack, dropChance: Float? = null) {
        val equipment = entity.equipment ?: return
        equipment.chestplate = item.clone()
        dropChance?.let {
            require(it.isFinite() && it in 0.0f..1.0f) { "Шанс выпадения должен быть от 0 до 1" }
            equipment.chestplateDropChance = it
        }
    }

    fun leggings(item: ItemStack, dropChance: Float? = null) {
        val equipment = entity.equipment ?: return
        equipment.leggings = item.clone()
        dropChance?.let {
            require(it.isFinite() && it in 0.0f..1.0f) { "Шанс выпадения должен быть от 0 до 1" }
            equipment.leggingsDropChance = it
        }
    }

    fun boots(item: ItemStack, dropChance: Float? = null) {
        val equipment = entity.equipment ?: return
        equipment.boots = item.clone()
        dropChance?.let {
            require(it.isFinite() && it in 0.0f..1.0f) { "Шанс выпадения должен быть от 0 до 1" }
            equipment.bootsDropChance = it
        }
    }

    fun potionTicks(type: PotionEffectType, ticks: Int, amplifier: Int = 0) {
        require(ticks >= 0) { "Длительность эффекта должна быть неотрицательным числом тиков" }
        require(amplifier in 0..255) { "Уровень эффекта зелья должен быть от 0 до 255" }
        entity.addPotionEffect(PotionEffect(type, ticks, amplifier))
    }

    fun removePotion(type: PotionEffectType): Boolean = entity.removePotionEffect(type)

    fun clearPotions() {
        entity.activePotionEffects.map { it.type }.forEach { entity.removePotionEffect(it) }
    }

    private fun requireMob(): Mob =
        entity as? Mob ?: throw IllegalStateException("${entity.type} не поддерживает Paper Mob Pathfinder")

    fun moveTo(location: Location, speed: Double = 1.0): Boolean {
        validateNavigationTarget(location, speed, "moveTo")
        return requireMob().pathfinder.moveTo(location, speed)
    }

    fun follow(target: LivingEntity, speed: Double = 1.0): Boolean {
        require(target.isValid && !target.isDead) { "Цель follow должна быть живой и валидной сущностью" }
        require(target.world == entity.world) { "Цель follow должна находиться в том же мире, что и моб" }
        validateNavigationSpeed(speed, "follow")
        return requireMob().pathfinder.moveTo(target, speed)
    }

    fun stopMoving() { requireMob().pathfinder.stopPathfinding() }

    fun lookAt(location: Location) {
        require(location.world == entity.world) { "Цель lookAt должна находиться в том же мире, что и моб" }
        require(location.x.isFinite() && location.y.isFinite() && location.z.isFinite()) {
            "Координаты lookAt должны быть конечными числами"
        }
        requireMob().lookAt(location)
    }

    fun aware(on: Boolean = true) { requireMob().setAware(on) }
    fun aggressive(on: Boolean = true) { requireMob().setAggressive(on) }

    fun navigation(
        canFloat: Boolean? = null,
        canOpenDoors: Boolean? = null,
        canPassDoors: Boolean? = null
    ) {
        val pathfinder = requireMob().pathfinder
        canFloat?.let { pathfinder.setCanFloat(it) }
        canOpenDoors?.let { pathfinder.setCanOpenDoors(it) }
        canPassDoors?.let { pathfinder.setCanPassDoors(it) }
    }

    private fun validateNavigationTarget(location: Location, speed: Double, operation: String) {
        validateNavigationSpeed(speed, operation)
        require(location.world == entity.world) { "Цель $operation должна находиться в том же мире, что и моб" }
        require(location.x.isFinite() && location.y.isFinite() && location.z.isFinite()) {
            "Координаты цели $operation должны быть конечными числами"
        }
    }

    private fun validateNavigationSpeed(speed: Double, operation: String) {
        require(speed.isFinite() && speed > 0.0 && speed <= 10.0) {
            "Скорость $operation должна быть конечным числом в диапазоне (0, 10]"
        }
    }

    // Base value by vanilla attribute name: attribute("attack_damage", 8.0).
    fun attribute(name: String, value: Double) {
        require(value.isFinite()) { "Значение атрибута '$name' должно быть конечным числом" }
        require(KSLEntityAttributes.setBase(entity, name, value)) { "у ${entity.type} нет атрибута '$name'" }
    }

    // Multiplier of the mob's own speed: speed(1.5) is 50% faster.
    fun speed(multiplier: Double) {
        require(multiplier.isFinite() && multiplier > 0.0) { "Множитель скорости должен быть конечным числом больше нуля" }
        require(KSLEntityAttributes.multiplyBase(entity, "movement_speed", multiplier)) { "у ${entity.type} нет атрибута movement_speed" }
    }

    fun damage(value: Double) = attribute("attack_damage", value)

    fun armor(value: Double) = attribute("armor", value)

    // seconds < 0 means until the mob dies.
    fun potion(type: PotionEffectType, seconds: Int = -1, amplifier: Int = 0) {
        require(seconds == -1 || seconds in 0..(Int.MAX_VALUE / 20)) {
            "Длительность зелья должна быть -1 (бесконечно) или неотрицательным числом секунд без переполнения"
        }
        require(amplifier in 0..255) { "Уровень эффекта зелья должен быть от 0 до 255" }
        val ticks = if (seconds == -1) PotionEffect.INFINITE_DURATION else seconds * 20
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
