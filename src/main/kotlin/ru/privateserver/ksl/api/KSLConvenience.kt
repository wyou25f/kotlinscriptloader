package ru.privateserver.ksl.api

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * Small convenience layer for addon authors. These are extensions rather than
 * KSLAPI interface members, so adding them does not break binary compatibility.
 *
 * Tasks created here are owned by the calling addon. Cancel them during its
 * onUnload callback unless they are intentionally short-lived.
 */

/** Snapshot of currently online players. Bukkit access must happen on the server thread. */
fun KSLAPI.onlinePlayers(): List<Player> = Bukkit.getOnlinePlayers().toList()

/** Finds an online player by their exact name. */
fun KSLAPI.findOnlinePlayer(name: String): Player? = Bukkit.getPlayerExact(name)

/** Sends literal text to every online player. MiniMessage markup is not interpreted. */
fun KSLAPI.broadcast(message: String): Int = Bukkit.broadcast(Component.text(message))

/** Sends an Adventure component to every online player. */
fun KSLAPI.broadcast(component: Component): Int = Bukkit.broadcast(component)

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
