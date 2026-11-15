package ru.privateserver.ksl.std.item

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.inventory.EquipmentSlot
import ru.privateserver.ksl.std.component.toMiniMessageComponent
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.entity.Projectile
import ru.privateserver.ksl.BukkitScriptContext
import ru.privateserver.ksl.KSLError
import ru.privateserver.ksl.KSLErrors
import ru.privateserver.ksl.KotlinScriptLoaderPlugin
import java.security.MessageDigest
import java.util.Locale

/**
 * A KSL-managed enchantment. It is stored as persistent item data and dispatched by KSL events;
 * it is deliberately not represented as a dynamically registered vanilla Bukkit Enchantment.
 */
class KSLCustomEnchantment internal constructor(
    val id: String,
    val namespacedId: String,
    val displayName: Component,
    val maxLevel: Int,
    val targets: Set<Material>,
    internal val conflictKeys: MutableSet<NamespacedKey>,
    val storageKey: NamespacedKey,
    internal val owner: String,
    internal val hitHandler: (KSLCustomEnchantHitContext.() -> Unit)?,
    internal val useHandler: (KSLCustomEnchantUseContext.() -> Unit)?,
    internal val breakHandler: (KSLCustomEnchantBreakContext.() -> Unit)?,
    internal val killHandler: (KSLCustomEnchantKillContext.() -> Unit)?,
    internal val damageTakenHandler: (KSLCustomEnchantDamageTakenContext.() -> Unit)? = null
) {
    override fun toString(): String = "KSLCustomEnchantment($namespacedId, maxLevel=$maxLevel)"
}

class KSLCustomEnchantHitContext internal constructor(
    val enchantment: KSLCustomEnchantment,
    val attacker: Player,
    val target: Entity,
    val item: ItemStack,
    val level: Int,
    val event: EntityDamageByEntityEvent
) {
    val player: Player get() = attacker
    val victim: Entity get() = target
}

class KSLCustomEnchantUseContext internal constructor(
    val enchantment: KSLCustomEnchantment,
    val player: Player,
    val item: ItemStack,
    val level: Int,
    val event: PlayerInteractEvent
) {
    val action get() = event.action
    val clickedBlock: Block? get() = event.clickedBlock
}

class KSLCustomEnchantBreakContext internal constructor(
    val enchantment: KSLCustomEnchantment,
    val player: Player,
    val block: Block,
    val item: ItemStack,
    val level: Int,
    val event: BlockBreakEvent
)

class KSLCustomEnchantKillContext internal constructor(
    val enchantment: KSLCustomEnchantment,
    val player: Player,
    val target: LivingEntity,
    val item: ItemStack,
    val level: Int,
    val event: EntityDeathEvent
)

/** Context for armor enchantments reacting to an entity-caused hit on their wearer. */
class KSLCustomEnchantDamageTakenContext internal constructor(
    val enchantment: KSLCustomEnchantment,
    val player: Player,
    val attacker: Entity,
    val item: ItemStack,
    val slot: EquipmentSlot,
    val level: Int,
    val event: EntityDamageByEntityEvent
)

/** Builder used by BukkitScriptContext.customEnchant. */
class KSLCustomEnchantBuilder internal constructor(val id: String) {
    private var displayName: Component = Component.text(id)
    private var maximumLevel: Int = 1
    private val targetMaterials = linkedSetOf<Material>()
    private val conflictIds = linkedSetOf<String>()
    private var hitHandler: (KSLCustomEnchantHitContext.() -> Unit)? = null
    private var useHandler: (KSLCustomEnchantUseContext.() -> Unit)? = null
    private var breakHandler: (KSLCustomEnchantBreakContext.() -> Unit)? = null
    private var killHandler: (KSLCustomEnchantKillContext.() -> Unit)? = null
    private var damageTakenHandler: (KSLCustomEnchantDamageTakenContext.() -> Unit)? = null

    /** Sets the displayed name. MiniMessage tags are supported. */
    fun name(value: String) {
        displayName = value.toMiniMessageComponent()
    }

    fun name(value: Component) {
        displayName = value
    }

    fun display(value: String) = name(value)

    fun maxLevel(level: Int) {
        require(level in 1..255) { "Custom enchantment max level must be between 1 and 255" }
        maximumLevel = level
    }

    /** Empty means that the enchantment can be applied to any item. */
    fun targets(vararg materials: Material) {
        targetMaterials.clear()
        targetMaterials.addAll(materials)
    }

    /** Bare ids refer to this script; use script-name:id to refer to another script. */
    fun conflicts(vararg ids: String) {
        conflictIds.clear()
        conflictIds.addAll(ids.map(String::trim).filter(String::isNotEmpty))
    }

    fun onHit(handler: KSLCustomEnchantHitContext.() -> Unit) {
        hitHandler = handler
    }

    fun onUse(handler: KSLCustomEnchantUseContext.() -> Unit) {
        useHandler = handler
    }

    fun onBreak(handler: KSLCustomEnchantBreakContext.() -> Unit) {
        breakHandler = handler
    }

    fun onKill(handler: KSLCustomEnchantKillContext.() -> Unit) {
        killHandler = handler
    }

    /** Runs for each equipped armor piece with this enchantment when the wearer takes entity damage. */
    fun onDamageTaken(handler: KSLCustomEnchantDamageTakenContext.() -> Unit) {
        damageTakenHandler = handler
    }

    internal fun build(owner: String, plugin: Plugin): KSLCustomEnchantment {
        val normalizedId = id.trim().lowercase(Locale.ROOT)
        require(normalizedId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "Custom enchantment id must use 1-64 lowercase letters, numbers, '.', '_' or '-': '$id'"
        }
        require(maximumLevel in 1..255) { "Custom enchantment max level must be between 1 and 255" }

        val storageKey = customEnchantKey(plugin, owner, normalizedId)
        val conflicts = conflictIds.map { reference ->
            val separator = reference.lastIndexOf(':')
            val targetOwner = if (separator >= 0) reference.substring(0, separator) else owner
            val targetId = if (separator >= 0) reference.substring(separator + 1) else reference
            require(targetOwner.isNotBlank() && targetId.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
                "Invalid custom enchantment conflict id '$reference'"
            }
            val targetKey = customEnchantKey(plugin, targetOwner, targetId)
            require(targetKey != storageKey) { "A custom enchantment cannot conflict with itself" }
            targetKey
        }.toSet()

        return KSLCustomEnchantment(
            id = normalizedId,
            namespacedId = "$owner:$normalizedId",
            displayName = displayName,
            maxLevel = maximumLevel,
            targets = targetMaterials.toSet(),
            conflictKeys = conflicts.toMutableSet(),
            storageKey = storageKey,
            owner = owner,
            hitHandler = hitHandler,
            useHandler = useHandler,
            breakHandler = breakHandler,
            killHandler = killHandler,
            damageTakenHandler = damageTakenHandler        )
    }
}

private fun customEnchantKey(plugin: Plugin, owner: String, id: String): NamespacedKey {
    val digest = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
    val ownerToken = digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return NamespacedKey(plugin, "custom-enchants/$ownerToken/$id")
}

/**
 * Script-scoped registry and event dispatcher. Definitions are removed on script reload/unload,
 * while already-applied item data remains stable for a later re-registration with the same id.
 */
internal class KSLCustomEnchantManager(private val plugin: KotlinScriptLoaderPlugin) : Listener {
    private val lock = Any()
    private val definitions = linkedMapOf<String, KSLCustomEnchantment>()

    fun register() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun register(
        owner: String,
        id: String,
        configure: KSLCustomEnchantBuilder.() -> Unit
    ): KSLCustomEnchantment {
        check(Bukkit.isPrimaryThread()) { "Custom enchantments must be registered on the server main thread" }
        require(owner.isNotBlank()) { "Script owner must not be blank" }
        val definition = KSLCustomEnchantBuilder(id).apply(configure).build(owner, plugin)
        synchronized(lock) {
            val existing = definitions[definition.namespacedId]
            require(existing == null || existing.owner == owner) {
                "Custom enchantment '${definition.namespacedId}' is already registered by another script"
            }
            definitions.values.forEach { existing ->
                // Conflict declarations are symmetric, independent of the order enchantments are registered.
                if (definition.storageKey in existing.conflictKeys) {
                    definition.conflictKeys.add(existing.storageKey)
                }
                if (existing.storageKey in definition.conflictKeys) {
                    existing.conflictKeys.add(definition.storageKey)
                }
            }
            definitions[definition.namespacedId] = definition
        }
        return definition
    }

    fun unregisterOwner(owner: String) {
        synchronized(lock) {
            definitions.entries.removeIf { it.value.owner == owner }
        }
    }

    fun close() {
        org.bukkit.event.HandlerList.unregisterAll(this)
        synchronized(lock) { definitions.clear() }
    }

    private fun currentDefinitions(): List<KSLCustomEnchantment> =
        synchronized(lock) { definitions.values.toList() }

    private fun dispatch(item: ItemStack, callback: (KSLCustomEnchantment, Int) -> Unit) {
        if (item.type.isAir) return
        val meta = item.itemMeta ?: return
        val data = meta.persistentDataContainer
        currentDefinitions().forEach { definition ->
            val level = data.get(definition.storageKey, PersistentDataType.INTEGER) ?: return@forEach
            if (level <= 0) return@forEach
            if (definition.targets.isNotEmpty() && item.type !in definition.targets) return@forEach
            callback(definition, level.coerceAtMost(definition.maxLevel))
        }
    }

    private fun report(definition: KSLCustomEnchantment, operation: String, failure: Throwable) {
        KSLErrors.log(
            plugin,
            definition.owner,
            "custom-enchant:${definition.id}:$operation",
            failure,
            KSLError.EVENT_HANDLER_FAILED
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val attacker = when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
        if (attacker != null) {
            val held = attacker.inventory.itemInMainHand
            dispatch(held) { definition, level ->
                val handler = definition.hitHandler ?: return@dispatch
                try {
                    val context = KSLCustomEnchantHitContext(definition, attacker, event.entity, held, level, event)
                    handler.invoke(context)
                } catch (failure: Throwable) {
                    report(definition, "onHit", failure)
                }
            }
        }

        val victim = event.entity as? Player ?: return
        val slots = listOf(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)
        victim.inventory.armorContents.forEachIndexed { index, armorItem ->
            val item = armorItem ?: return@forEachIndexed
            val slot = slots.getOrNull(index) ?: return@forEachIndexed
            dispatch(item) { definition, level ->
                val handler = definition.damageTakenHandler ?: return@dispatch
                try {
                    handler.invoke(KSLCustomEnchantDamageTakenContext(definition, victim, event.damager, item, slot, level, event))
                } catch (failure: Throwable) {
                    report(definition, "onDamageTaken", failure)
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        val held = event.item ?: event.player.inventory.itemInMainHand
        dispatch(held) { definition, level ->
            val handler = definition.useHandler ?: return@dispatch
            try {
                val context = KSLCustomEnchantUseContext(definition, event.player, held, level, event)
                handler.invoke(context)
            } catch (failure: Throwable) {
                report(definition, "onUse", failure)
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val held = event.player.inventory.itemInMainHand
        dispatch(held) { definition, level ->
            val handler = definition.breakHandler ?: return@dispatch
            try {
                val context = KSLCustomEnchantBreakContext(definition, event.player, event.block, held, level, event)
                handler.invoke(context)
            } catch (failure: Throwable) {
                report(definition, "onBreak", failure)
            }
        }
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val held = killer.inventory.itemInMainHand
        dispatch(held) { definition, level ->
            val handler = definition.killHandler ?: return@dispatch
            try {
                val context = KSLCustomEnchantKillContext(definition, killer, event.entity, held, level, event)
                handler.invoke(context)
            } catch (failure: Throwable) {
                report(definition, "onKill", failure)
            }
        }
    }
}

/** Registers a persistent, script-owned custom enchantment. */
fun BukkitScriptContext.customEnchant(
    id: String,
    configure: KSLCustomEnchantBuilder.() -> Unit
): KSLCustomEnchantment =
    internalPlugin.customEnchantManager.register(scriptName, id, configure)

/** Descriptive alias for customEnchant. */
fun BukkitScriptContext.registerCustomEnchant(
    id: String,
    configure: KSLCustomEnchantBuilder.() -> Unit
): KSLCustomEnchantment = customEnchant(id, configure)

/** Creates a cloned item carrying the custom enchantment at the given level. */
fun ItemStack.withCustomEnchant(
    enchantment: KSLCustomEnchantment,
    level: Int = 1
): ItemStack {
    require(level in 1..enchantment.maxLevel) {
        "Level for ${enchantment.namespacedId} must be between 1 and ${enchantment.maxLevel}"
    }
    require(enchantment.targets.isEmpty() || type in enchantment.targets) {
        "${enchantment.namespacedId} cannot be applied to $type"
    }

    val result = clone()
    val meta = result.itemMeta ?: throw IllegalArgumentException("Item $type does not support item metadata")
    val data = meta.persistentDataContainer
    val conflict = enchantment.conflictKeys.firstOrNull { data.has(it, PersistentDataType.INTEGER) }
    require(conflict == null) {
        "Item already has an enchantment that conflicts with ${enchantment.namespacedId}"
    }

    val oldLore = meta.lore().orEmpty()
    val cleanedLore = oldLore.filterNot { line ->
        (1..enchantment.maxLevel).any { previousLevel ->
            line == enchantmentLore(enchantment, previousLevel)
        }
    }
    data.set(enchantment.storageKey, PersistentDataType.INTEGER, level)
    meta.lore(cleanedLore + enchantmentLore(enchantment, level))
    result.itemMeta = meta
    return result
}

fun ItemStack.customEnchant(enchantment: KSLCustomEnchantment, level: Int = 1): ItemStack =
    withCustomEnchant(enchantment, level)

fun ItemStack.customEnchantLevel(enchantment: KSLCustomEnchantment): Int =
    itemMeta?.persistentDataContainer?.get(enchantment.storageKey, PersistentDataType.INTEGER) ?: 0

fun ItemStack.hasCustomEnchant(enchantment: KSLCustomEnchantment): Boolean =
    customEnchantLevel(enchantment) > 0

fun ItemStack.withoutCustomEnchant(enchantment: KSLCustomEnchantment): ItemStack {
    val result = clone()
    val meta = result.itemMeta ?: return result
    meta.persistentDataContainer.remove(enchantment.storageKey)
    val cleanedLore = meta.lore().orEmpty().filterNot { line ->
        (1..enchantment.maxLevel).any { previousLevel ->
            line == enchantmentLore(enchantment, previousLevel)
        }
    }
    meta.lore(cleanedLore.takeIf { it.isNotEmpty() })
    result.itemMeta = meta
    return result
}

fun ItemStack.removeCustomEnchant(enchantment: KSLCustomEnchantment): ItemStack =
    withoutCustomEnchant(enchantment)

private fun enchantmentLore(enchantment: KSLCustomEnchantment, level: Int): Component =
    enchantment.displayName.append(Component.text(" $level", NamedTextColor.GRAY))
