package ru.privateserver.ksl.dsl

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.inventory.ItemStack
import ru.privateserver.ksl.BukkitScriptContext
import java.util.concurrent.ThreadLocalRandom

// Short aliases and helpers for scripts. Everything here is a thin wrapper over public API,
// so it is safe to import into scripts and to copy into an addon as a starting point.

private val mm = MiniMessage.miniMessage()

// ---- time (ticks) ----

val Int.sec: Long get() = this * 20L
val Long.sec: Long get() = this * 20L
val Int.mins: Long get() = this * 1200L

// ---- messaging ----

fun CommandSender.msg(text: String) = sendMessage(mm.deserialize(text))

fun Player.actionBar(text: String) = sendActionBar(mm.deserialize(text))

// ---- players / random ----

val online: Collection<Player> get() = Bukkit.getOnlinePlayers()

fun playerNamed(name: String): Player? = Bukkit.getPlayerExact(name)

fun chance(percent: Double): Boolean = ThreadLocalRandom.current().nextDouble() * 100.0 < percent

// ---- items ----

private fun noItalic(component: Component): Component =
    component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

// Name and lore are MiniMessage; vanilla's italic default for custom names is switched off.
fun item(material: Material, name: String? = null, lore: List<String> = emptyList(), amount: Int = 1): ItemStack {
    val stack = ItemStack(material, amount.coerceAtLeast(1))
    if (name == null && lore.isEmpty()) return stack
    stack.editMeta { meta ->
        name?.let { meta.displayName(noItalic(mm.deserialize(it))) }
        if (lore.isNotEmpty()) meta.lore(lore.map { noItalic(mm.deserialize(it)) })
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
