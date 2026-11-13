package ru.privateserver.ksl.dsl

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player

private val convenienceMiniMessage = MiniMessage.miniMessage()

/** Console sender shortcut for scripts. */
val console: ConsoleCommandSender
    get() = Bukkit.getConsoleSender()

/** Number of currently online players. */
val onlineCount: Int
    get() = Bukkit.getOnlinePlayers().size

/** Returns a random online player, or null when the server is empty. */
fun randomOnlinePlayer(): Player? = Bukkit.getOnlinePlayers().randomOrNull()

/** Broadcasts literal text without interpreting formatting codes or MiniMessage tags. */
fun broadcastPlain(message: String): Int = Bukkit.broadcastMessage(message)

/** Broadcasts MiniMessage-formatted text to all online players. */
fun broadcast(message: String): Int = Bukkit.broadcast(convenienceMiniMessage.deserialize(message))
