package ru.privateserver.ksl.api

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Small convenience layer for addon authors. These are extensions rather than
 * KSLAPI interface members, so adding them does not break binary compatibility.
 *
 * The scheduler helpers return raw BukkitTask handles and are not cancelled
 * automatically on addon unload. Use addonScope() to group tasks, listeners,
 * subscriptions and external resources under one explicit lifecycle owner.
 */

/** Snapshot of currently online players. Bukkit access must happen on the server thread. */
fun KSLAPI.onlinePlayers(): List<Player> = Bukkit.getOnlinePlayers().toList()

/** Finds an online player by their exact name. */
fun KSLAPI.findOnlinePlayer(name: String): Player? = Bukkit.getPlayerExact(name)

/** Finds an online player by UUID. */
fun KSLAPI.findOnlinePlayer(uniqueId: UUID): Player? = Bukkit.getPlayer(uniqueId)

/** Checks whether a player UUID currently belongs to an online player. */
fun KSLAPI.isPlayerOnline(uniqueId: UUID): Boolean = Bukkit.getPlayer(uniqueId)?.isOnline == true

/** Number of currently online players. */
fun KSLAPI.onlinePlayerCount(): Int = Bukkit.getOnlinePlayers().size

/** Sends literal text to every online player. MiniMessage markup is not interpreted. */
fun KSLAPI.broadcast(message: String): Int = Bukkit.broadcast(Component.text(message))

/** Sends an Adventure component to every online player. */
fun KSLAPI.broadcast(component: Component): Int = Bukkit.broadcast(component)

/** Sends MiniMessage-formatted text to every online player. */
fun KSLAPI.broadcastRich(message: String): Int =
    Bukkit.broadcast(MiniMessage.miniMessage().deserialize(message))

/** Whether the current caller is on the Bukkit server thread. */
fun KSLAPI.isServerThread(): Boolean = Bukkit.isPrimaryThread()

/** Schedules work on the server thread on its next tick. */
fun KSLAPI.scheduleSync(block: () -> Unit): BukkitTask =
    Bukkit.getScheduler().runTask(kslPlugin, Runnable(block))

/** Schedules work on a Bukkit async worker. Do not access world/entity/inventory state there. */
fun KSLAPI.scheduleAsync(block: () -> Unit): BukkitTask =
    Bukkit.getScheduler().runTaskAsynchronously(kslPlugin, Runnable(block))

/** Schedules work on the server thread after [delayTicks]. */
fun KSLAPI.scheduleSyncLater(delayTicks: Long, block: () -> Unit): BukkitTask {
    require(delayTicks >= 0L) { "delayTicks must not be negative" }
    return Bukkit.getScheduler().runTaskLater(kslPlugin, Runnable(block), delayTicks)
}

/** Schedules work asynchronously after [delayTicks]. */
fun KSLAPI.scheduleAsyncLater(delayTicks: Long, block: () -> Unit): BukkitTask {
    require(delayTicks >= 0L) { "delayTicks must not be negative" }
    return Bukkit.getScheduler().runTaskLaterAsynchronously(kslPlugin, Runnable(block), delayTicks)
}

/** Repeats work on the server thread. [periodTicks] must be positive. */
fun KSLAPI.scheduleSyncRepeating(
    initialDelayTicks: Long = 0L,
    periodTicks: Long,
    block: () -> Unit
): BukkitTask {
    require(initialDelayTicks >= 0L) { "initialDelayTicks must not be negative" }
    require(periodTicks > 0L) { "periodTicks must be positive" }
    return Bukkit.getScheduler().runTaskTimer(kslPlugin, Runnable(block), initialDelayTicks, periodTicks)
}

/** Repeats work asynchronously. Bukkit world/entity/inventory access is not safe in [block]. */
fun KSLAPI.scheduleAsyncRepeating(
    initialDelayTicks: Long = 0L,
    periodTicks: Long,
    block: () -> Unit
): BukkitTask {
    require(initialDelayTicks >= 0L) { "initialDelayTicks must not be negative" }
    require(periodTicks > 0L) { "periodTicks must be positive" }
    return Bukkit.getScheduler().runTaskTimerAsynchronously(kslPlugin, Runnable(block), initialDelayTicks, periodTicks)
}


/**
 * Creates an explicit resource owner for an addon.
 *
 * Keep the returned scope for the addon's lifetime and call close() from onUnload().
 * Every resource registered through the scope is released in reverse order.
 */
fun KSLAPI.addonScope(): KSLAddonScope = KSLAddonScope.create(this)


/** Read-only snapshot of JVM memory, processor and thread metrics. */
fun KSLAPI.runtimeStats(): ru.privateserver.ksl.rt.KSLRuntimeSnapshot =
    ru.privateserver.ksl.rt.KSLRuntimeMonitor.snapshot()

/** Alias for [runtimeStats] for concise addon code. */
fun KSLAPI.runtime(): ru.privateserver.ksl.rt.KSLRuntimeSnapshot = runtimeStats()

/** Number of processors visible to this JVM; this does not reserve CPU cores. */
fun KSLAPI.availableProcessors(): Int = runtimeStats().processors

/** Current JVM heap usage in bytes; heap sizing remains controlled by Java launch flags. */
fun KSLAPI.heapUsedBytes(): Long = runtimeStats().heapUsedBytes

/** Maximum JVM heap size in bytes, or a negative value when the JVM reports it as undefined. */
fun KSLAPI.heapMaxBytes(): Long = runtimeStats().heapMaxBytes
