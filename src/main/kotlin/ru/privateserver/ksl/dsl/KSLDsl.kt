package ru.privateserver.ksl.dsl

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.OfflinePlayer
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.inventory.ItemStack
import ru.privateserver.ksl.BukkitScriptContext
import ru.privateserver.ksl.KSLHook
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

// Short aliases and helpers for scripts. Everything here is a thin wrapper over public API,
// so it is safe to import into scripts and to copy into an addon as a starting point.

private val miniMessage = MiniMessage.miniMessage()

// ---- messaging ----

fun CommandSender.msg(text: String) = sendMessage(miniMessage.deserialize(text))

fun Player.actionBar(text: String) = sendActionBar(miniMessage.deserialize(text))

// "<red>text".mm — a MiniMessage string as an Adventure Component.
val String.mm: Component get() = miniMessage.deserialize(this)

// Times are in ticks.
fun Player.title(title: String, subtitle: String = "", fadeInTicks: Int = 10, stayTicks: Int = 40, fadeOutTicks: Int = 10) =
    showTitle(
        Title.title(
            miniMessage.deserialize(title),
            miniMessage.deserialize(subtitle),
            Title.Times.times(Duration.ofMillis(fadeInTicks * 50L), Duration.ofMillis(stayTicks * 50L), Duration.ofMillis(fadeOutTicks * 50L))
        )
    )

// By namespaced key, e.g. "entity.player.levelup": no Sound enum import needed.
fun Player.sound(key: String, volume: Float = 1f, pitch: Float = 1f) = playSound(location, key, volume, pitch)

@Suppress("DEPRECATION")
fun Player.heal() {
    health = maxHealth
}

// ---- players / random ----

val online: Collection<Player> get() = Bukkit.getOnlinePlayers()

fun playerNamed(name: String): Player? = Bukkit.getPlayerExact(name)

fun chance(percent: Double): Boolean = ThreadLocalRandom.current().nextDouble() * 100.0 < percent

// ---- locations ----

// Null when the world is not loaded.
fun loc(world: String, x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): Location? =
    Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

fun Location.offset(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0): Location = clone().add(x, y, z)

fun Location.playersNear(radius: Double): Collection<Player> = world?.getNearbyPlayers(this, radius) ?: emptyList()

// ---- items ----

private fun noItalic(component: Component): Component =
    component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

// Name and lore are MiniMessage; vanilla's italic default for custom names is switched off.
fun item(material: Material, name: String? = null, lore: List<String> = emptyList(), amount: Int = 1): ItemStack {
    val stack = ItemStack(material, amount.coerceAtLeast(1))
    if (name == null && lore.isEmpty()) return stack
    stack.editMeta { meta ->
        name?.let { meta.displayName(noItalic(miniMessage.deserialize(it))) }
        if (lore.isNotEmpty()) meta.lore(lore.map { noItalic(miniMessage.deserialize(it)) })
    }
    return stack
}

// Whatever does not fit is dropped at the player's feet instead of silently vanishing. True if everything fit.
fun Player.giveItem(stack: ItemStack): Boolean {
    val leftover = inventory.addItem(stack)
    leftover.values.forEach { world.dropItemNaturally(location, it) }
    return leftover.isEmpty()
}

// ---- script context ----

fun BukkitScriptContext.log(message: String) = plugin.logger.info("[$scriptName] $message")

fun BukkitScriptContext.logWarn(message: String) = plugin.logger.warning("[$scriptName] $message")

fun BukkitScriptContext.logError(message: String) = plugin.logger.severe("[$scriptName] $message")

fun BukkitScriptContext.later(ticks: Long, block: () -> Unit): Int = delay(ticks, block)

fun BukkitScriptContext.laterAsync(ticks: Long, block: () -> Unit): Int = delayAsync(ticks, block)

fun BukkitScriptContext.sync(block: () -> Unit): Int = runSync(block)

fun BukkitScriptContext.async(block: () -> Unit): Int = runAsync(block)

inline fun <reified T : Event> BukkitScriptContext.on(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    crossinline action: T.() -> Unit
) = onEvent<T>(priority, ignoreCancelled, action)

fun BukkitScriptContext.command(
    name: String,
    aliases: List<String> = emptyList(),
    permission: String? = null,
    tabComplete: ((Player, Array<String>) -> List<String>)? = null,
    action: (Player, Array<String>) -> Unit
) = registerCommand(name, aliases, permission, tabComplete, action)

fun BukkitScriptContext.commandAny(
    name: String,
    aliases: List<String> = emptyList(),
    permission: String? = null,
    tabComplete: ((CommandSender, Array<String>) -> List<String>)? = null,
    action: (CommandSender, Array<String>) -> Unit
) = registerCommandAny(name, aliases, permission, tabComplete, action)

// config.getOrSetDefault(path, default) in one word.
inline fun <reified T> BukkitScriptContext.cfg(path: String, default: T): T =
    with(this) { config.getOrSetDefault(path, default) }

// ---- event shortcuts ----

fun BukkitScriptContext.onJoin(action: PlayerJoinEvent.() -> Unit) = onEvent<PlayerJoinEvent>(action = action)

fun BukkitScriptContext.onQuit(action: PlayerQuitEvent.() -> Unit) = onEvent<PlayerQuitEvent>(action = action)

fun BukkitScriptContext.onPlayerDeath(action: PlayerDeathEvent.() -> Unit) = onEvent<PlayerDeathEvent>(action = action)

fun BukkitScriptContext.onRespawn(action: PlayerRespawnEvent.() -> Unit) = onEvent<PlayerRespawnEvent>(action = action)

fun BukkitScriptContext.onBlockBreak(action: BlockBreakEvent.() -> Unit) = onEvent<BlockBreakEvent>(action = action)

fun BukkitScriptContext.onBlockPlace(action: BlockPlaceEvent.() -> Unit) = onEvent<BlockPlaceEvent>(action = action)

fun BukkitScriptContext.onInteract(action: PlayerInteractEvent.() -> Unit) = onEvent<PlayerInteractEvent>(action = action)

// Fires on Paper's async chat thread: do not touch the world directly, wrap that in sync { }.
fun BukkitScriptContext.onChat(action: AsyncChatEvent.() -> Unit) = onEvent<AsyncChatEvent>(action = action)

// %ksl_<key>% for PlaceholderAPI; removed when the script unloads.
fun BukkitScriptContext.placeholder(key: String, resolver: (OfflinePlayer) -> String?) = registerPlaceholder(key, resolver)

// ---- hooks (integrations registered by KSL or by addons) ----

fun BukkitScriptContext.hasHook(hookId: String): Boolean = plugin.addonManager.getHook(hookId) != null

inline fun <reified T : KSLHook> BukkitScriptContext.hook(hookId: String): T? =
    plugin.addonManager.getHook(hookId, T::class.java)

inline fun <reified T : KSLHook> BukkitScriptContext.requireHook(hookId: String): T =
    hook<T>(hookId) ?: error("Хук '$hookId' недоступен: установлен ли плагин и загружен ли аддон с этим хуком?")
