package ru.privateserver.ksl

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class KSLCommandExecutor(private val plugin: KotlinScriptLoaderPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("ksl.admin")) {
            sender.sendMessage("§cНет прав. Требуется: ksl.admin")
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            null, "reload" -> {
                sender.sendMessage("§7Перезагружаю скрипты...")
                val (loaded, failed) = plugin.reloadScripts()
                val color = if (failed == 0) "§a" else "§e"
                sender.sendMessage("${color}KSL: $loaded загружено, $failed с ошибками.")
            }

            "addons" -> {
                val addons = plugin.addonManager.registeredAddons()
                if (addons.isEmpty()) {
                    sender.sendMessage("§eKSL: нет загруженных аддонов.")
                } else {
                    sender.sendMessage("§aKSL Аддоны §7(${addons.size}):")
                    addons.forEach { addon ->
                        val desc = if (addon.addonDescription.isNotBlank()) " §8— ${addon.addonDescription}" else ""
                        sender.sendMessage("  §f${addon.addonId} §7v${addon.addonVersion}$desc")
                        if (addon.dependsOn.isNotEmpty()) {
                            val deps = addon.dependsOn.joinToString(", ") { dep ->
                                if (plugin.addonManager.isAddonLoaded(dep)) "§a$dep§7" else "§c$dep (нет)§7"
                            }
                            sender.sendMessage("    §7зависит от: $deps")
                        }
                    }
                }
            }

            "services" -> {
                val keys = plugin.addonManager.getServiceKeys()
                if (keys.isEmpty()) {
                    sender.sendMessage("§eKSL: нет зарегистрированных сервисов.")
                } else {
                    sender.sendMessage("§aKSL Сервисы §7(${keys.size}):")
                    keys.sorted().forEach { sender.sendMessage("  §f$it") }
                }
            }

            "discord" -> {
                val discord = plugin.discord
                if (discord == null) {
                    sender.sendMessage("§eKSL Discord: нет настроенных вебхуков в config.yml")
                } else {
                    val channels = discord.channels()
                    sender.sendMessage("§aKSL Discord §7— каналы (${channels.size}): §f${channels.joinToString(", ")}")
                }
            }

            "libraries" -> {
                val keys = plugin.libraryRegistry.exportedKeys()
                if (keys.isEmpty()) {
                    sender.sendMessage("§eKSL: нет экспортированных библиотек.")
                } else {
                    sender.sendMessage("§aKSL Библиотеки §7(${keys.size}):")
                    keys.sorted().forEach { key ->
                        val owner = plugin.libraryRegistry.ownerOf(key) ?: "?"
                        sender.sendMessage("  §f$key §7— из '$owner'")
                    }
                }
            }

            "errors" -> {
                val total = KSLErrors.totalCount()
                val last = KSLErrors.lastError()
                sender.sendMessage("§aKSL Ошибки §7— всего с последнего старта: §f$total")
                if (last != null) {
                    val ago = (System.currentTimeMillis() - last.timestamp) / 1000
                    sender.sendMessage("§7Последняя: §f[${last.scriptName}] ${last.where} §8(${ago}с назад)")
                    sender.sendMessage("§7${last.message}")
                } else {
                    sender.sendMessage("§7Ошибок не зафиксировано.")
                }
            }

            "validate" -> {
                sender.sendMessage("§7Проверяю скрипты без загрузки (dry-run)...")
                val results = plugin.validateAllScripts()
                val broken = results.filterValues { it.isNotEmpty() }
                if (broken.isEmpty()) {
                    sender.sendMessage("§a✔ Все скрипты (${results.size}) компилируются без ошибок.")
                } else {
                    sender.sendMessage("§c${broken.size} из ${results.size} скриптов с проблемами:")
                    broken.forEach { (scriptName, issues) ->
                        sender.sendMessage("  §f$scriptName§7:")
                        issues.forEach { sender.sendMessage("    §c$it") }
                    }
                }
            }

            "doctor" -> {
                sender.sendMessage("§9§l▶ KSL Диагностика")
                val lines = plugin.runDiagnostics()
                lines.forEach { line ->
                    val mark = if (line.ok) "§a✔" else "§c✘"
                    sender.sendMessage("$mark §f${line.label}§7: ${line.detail}")
                }
                val failedCount = lines.count { !it.ok }
                if (failedCount == 0) {
                    sender.sendMessage("§a§lВсё в порядке.")
                } else {
                    sender.sendMessage("§e§l$failedCount пункт(ов) требуют внимания — см. выше.")
                }
            }

            "sandbox" -> {
                sender.sendMessage("§aKSL Sandbox: §f${if (plugin.sandboxEnabled) "§aвключён" else "§eвыключен"}")
                if (plugin.sandboxEnabled) {
                    sender.sendMessage("§7Скрипты проверяются на опасные паттерны перед компиляцией.")
                }
            }

            else -> sender.sendMessage("§cИспользование: /ksl [reload|addons|services|libraries|discord|sandbox|errors|doctor|validate]")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<String>
    ): List<String> {
        if (!sender.hasPermission("ksl.admin")) return emptyList()
        if (args.size == 1)
            return listOf("reload", "addons", "services", "libraries", "discord", "sandbox", "errors", "doctor", "validate")
                .filter { it.startsWith(args[0].lowercase()) }
        return emptyList()
    }
}
