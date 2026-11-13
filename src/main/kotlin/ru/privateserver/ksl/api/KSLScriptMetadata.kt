package ru.privateserver.ksl.api

/**
 * Shared script-definition metadata consumed by the runtime and IDE tooling.
 *
 * Keep these values in sync with the KSL script template. IDE integrations should
 * read this metadata instead of maintaining a second, divergent import list.
 */
const val KSL_SCRIPT_FILE_EXTENSION: String = "kts"
const val KSL_SCRIPT_JVM_TARGET: String = "21"
const val KSL_SCRIPT_CONTEXT_CLASS: String = "ru.privateserver.ksl.BukkitScriptContext"

/** Imports available to every KSL script without an explicit import statement. */
val KSL_SCRIPT_DEFAULT_IMPORTS: List<String> = listOf(
    "org.bukkit.Bukkit",
    "org.bukkit.Material",
    "org.bukkit.Particle",
    "org.bukkit.Location",
    "org.bukkit.ChatColor",
    "org.bukkit.OfflinePlayer",
    "org.bukkit.World",
    "org.bukkit.NamespacedKey",
    "org.bukkit.entity.*",
    "org.bukkit.event.*",
    "org.bukkit.event.player.*",
    "org.bukkit.event.block.*",
    "org.bukkit.event.entity.*",
    "org.bukkit.event.inventory.*",
    "org.bukkit.event.world.*",
    "org.bukkit.event.server.*",
    "org.bukkit.event.weather.*",
    "org.bukkit.event.vehicle.*",
    "org.bukkit.event.hanging.*",
    "org.bukkit.inventory.*",
    "org.bukkit.inventory.meta.*",
    "org.bukkit.command.*",
    "org.bukkit.scheduler.*",
    "org.bukkit.configuration.file.*",
    "org.bukkit.potion.*",
    "org.bukkit.util.*",
    "org.bukkit.block.*",
    "org.bukkit.boss.*",
    "org.bukkit.attribute.*",
    "org.bukkit.enchantments.*",
    "org.bukkit.metadata.*",
    "org.bukkit.persistence.*",
    "org.bukkit.permissions.*",
    "org.bukkit.advancement.*",
    "net.kyori.adventure.text.*",
    "net.kyori.adventure.text.format.*",
    "net.kyori.adventure.text.minimessage.*",
    "net.kyori.adventure.title.*",
    "net.kyori.adventure.sound.*",
    "net.kyori.adventure.audience.*",
    "io.papermc.paper.event.player.*",
    "io.papermc.paper.event.block.*",
    "io.papermc.paper.event.entity.*",
    "ru.privateserver.ksl.dsl.*",
    "ru.privateserver.ksl.api.*",
    "ru.privateserver.ksl.std.text.*",
    "ru.privateserver.ksl.std.math.*",
    "ru.privateserver.ksl.std.time.*",
    "ru.privateserver.ksl.std.player.*",
    "ru.privateserver.ksl.std.inventory.*",
    "ru.privateserver.ksl.std.item.*",
    "ru.privateserver.ksl.std.component.*",
    "ru.privateserver.ksl.std.world.*",
    "ru.privateserver.ksl.rt.*",
    "ru.privateserver.ksl.meslib2.*",
    "java.util.UUID",
    "java.time.*",
    "java.util.concurrent.*"
)
