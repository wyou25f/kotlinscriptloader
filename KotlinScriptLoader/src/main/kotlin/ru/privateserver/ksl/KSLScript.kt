package ru.privateserver.ksl

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm

private val BASE_IMPORTS = arrayOf(
    "org.bukkit.Bukkit",
    "org.bukkit.Material",
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
    "java.util.UUID",
    "java.time.*",
    "java.util.concurrent.*"
)

fun buildScriptCompilationConfig(addonManager: KSLAddonManager): ScriptCompilationConfiguration {
    val allImports = BASE_IMPORTS.toList() + addonManager.getExtraImports()
    return ScriptCompilationConfiguration {
        implicitReceivers(BukkitScriptContext::class)
        compilerOptions("-jvm-target", "21")
        defaultImports(*allImports.toTypedArray())
        jvm {
            dependenciesFromClassloader(
                classLoader = BukkitScriptContext::class.java.classLoader,
                wholeClasspath = true
            )
        }
    }
}

@KotlinScript(fileExtension = "kts")
abstract class KSLScript(@Suppress("UNUSED_PARAMETER") source: SourceCode)
