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

            "sandbox" -> {
                sender.sendMessage("§aKSL Sandbox: §f${if (plugin.sandboxEnabled) "§aвключён" else "§eвыключен"}")
                if (plugin.sandboxEnabled) {
                    sender.sendMessage("§7Скрипты проверяются на опасные паттерны перед компиляцией.")
                }
            }

            else -> sender.sendMessage("§cИспользование: /ksl [reload|addons|services|discord|sandbox]")
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<String>
    ): List<String> {
        if (!sender.hasPermission("ksl.admin")) return emptyList()
        if (args.size == 1)
            return listOf("reload", "addons", "services", "discord", "sandbox")
                .filter { it.startsWith(args[0].lowercase()) }
        return emptyList()
    }
}
