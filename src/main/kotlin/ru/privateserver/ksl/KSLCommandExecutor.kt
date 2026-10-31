package ru.privateserver.ksl

import org.bukkit.Bukkit
import java.io.File
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
            null, "help" -> sendHelp(sender)

            "reload" -> {
                val target = args.getOrNull(1)
                when {
                    target == null -> {
                        sender.sendMessage("§7Перезагружаю все скрипты...")
                        val (loaded, failed) = plugin.reloadScripts()
                        val color = if (failed == 0) "§a" else "§e"
                        sender.sendMessage("${color}KSL: $loaded загружено, $failed с ошибками.")
                    }
                    target.equals("changed", ignoreCase = true) -> {
                        val report = plugin.reloadChanged()
                        val color = if (report.failed == 0) "§a" else "§e"
                        sender.sendMessage("${color}KSL: перезагружено ${report.reloaded}, выгружено ${report.unloaded}, без изменений ${report.unchanged}, с ошибками ${report.failed}.")
                    }
                    target.endsWith("/") || File(plugin.scriptsFolder, target).isDirectory -> {
                        val result = plugin.reloadFolder(target)
                        if (result == null) {
                            sender.sendMessage("§cВ папке '$target' нет скриптов.")
                        } else {
                            val color = if (result.second == 0) "§a" else "§e"
                            sender.sendMessage("${color}KSL: папка '$target': ${result.first} загружено, ${result.second} с ошибками.")
                        }
                    }
                    else -> {
                        sender.sendMessage("§7Перезагружаю '$target.kts'...")
                        if (plugin.reloadScript(target)) {
                            sender.sendMessage("§aKSL: '$target.kts' перезагружен, остальные скрипты не тронуты.")
                        } else {
                            sender.sendMessage("§cНе удалось перезагрузить '$target.kts' — см. консоль.")
                        }
                    }
                }
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
                    sender.sendMessage("§7Код §e${last.code.code} §8— §7${last.code.title}. Подробнее: §f/ksl error ${last.code.code}")
                } else {
                    sender.sendMessage("§7Ошибок не зафиксировано.")
                }
            }

            "error" -> {
                val query = args.getOrNull(1)
                if (query == null) {
                    sender.sendMessage("§7Использование: §f/ksl error <код> §7(например /ksl error ${KSLError.SCRIPT_COMPILE.code}). Категории:")
                    KSLErrorCategory.entries.forEach { sender.sendMessage("  §e${it.letter} §8— §7${it.title}") }
                } else {
                    val error = KSLError.find(query)
                    if (error == null) {
                        sender.sendMessage("§cКод '$query' не найден. Полный список: docs/Error-Codes.md")
                    } else {
                        sender.sendMessage("§e${error.code} §8— §f${error.title} §8(${error.category.title})")
                        sender.sendMessage("§7Что делать: ${error.hint}")
                    }
                }
            }

            "validate" -> {
                sender.sendMessage("§7Проверяю скрипты без загрузки (dry-run), это может занять несколько секунд...")
                background(sender, { plugin.validateAllScripts() }) { results ->
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
            }

            "doctor" -> {
                sender.sendMessage("§9§l▶ KSL Диагностика §7(проверка компилятора занимает несколько секунд)")
                // The first Kotlin compile initialises the whole compiler; on the server thread that froze the tick for 10+ s.
                background(sender, { plugin.selfTestCompiler() }) { compiler ->
                    val lines = plugin.runDiagnostics(compiler)
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
            }

            "dbmigrate" -> {
                if (!plugin.dialect.isRemote) {
                    sender.sendMessage("§eKSL: сейчас используется SQLite, переносить некуда. Укажи database.type: mysql или mariadb в config.yml и перезапусти сервер.")
                    return true
                }
                val confirmed = args.getOrNull(1).equals("confirm", ignoreCase = true)
                sender.sendMessage("§7Читаю ksl.db...")
                background(sender, {
                    val migrator = KSLDbMigrator(plugin)
                    val found = migrator.inspect()
                    if (found != null && confirmed) found to migrator.migrate() else found to null
                }) { (found, migrated) ->
                    when {
                        found == null -> sender.sendMessage("§eKSL: файл ksl.db не найден в папке плагина — переносить нечего.")
                        migrated == null -> {
                            sender.sendMessage("§aВ ksl.db найдено: §fpersist ${found.persist}§a, §ftable ${found.table}§a записей.")
                            sender.sendMessage("§7Они будут скопированы в ${plugin.dialect.label} (существующие ключи перезапишутся, ksl.db не изменится).")
                            sender.sendMessage("§7Для запуска: §f/ksl dbmigrate confirm")
                        }
                        else -> sender.sendMessage("§a✔ Перенесено: §fpersist ${migrated.persist}§a, §ftable ${migrated.table}§a. Файл ksl.db не тронут.")
                    }
                }
            }

            "sandbox" -> {
                sender.sendMessage("§aKSL Sandbox: §f${if (plugin.sandboxEnabled) "§aвключён" else "§eвыключен"}")
                if (plugin.sandboxEnabled) {
                    sender.sendMessage("§7Статический фильтр опасных вызовов перед компиляцией. Это не изоляция: скрипты выполняются с правами сервера.")
                }
            }

            else -> sendHelp(sender, unknown = args[0])
        }
        return true
    }

    private fun sendHelp(sender: CommandSender, unknown: String? = null) {
        if (unknown != null) sender.sendMessage("§cНеизвестная подкоманда '$unknown'.")
        sender.sendMessage("§9§lKSL §7— команды:")
        listOf(
            "reload [скрипт|папка/|changed]" to "перезагрузить всё, один скрипт, папку или только изменённые",
            "addons" to "загруженные аддоны",
            "services" to "зарегистрированные сервисы",
            "libraries" to "экспортированные библиотеки",
            "discord" to "настроенные Discord-каналы",
            "sandbox" to "состояние sandbox",
            "errors" to "последняя ошибка скриптов",
            "error <код>" to "расшифровка кода ошибки, например ${KSLError.SCRIPT_COMPILE.code}",
            "doctor" to "диагностика (компилятор, БД, хуки)",
            "validate" to "проверить скрипты без загрузки",
            "dbmigrate [confirm]" to "перенести ksl.db в MySQL/MariaDB"
        ).forEach { (name, description) -> sender.sendMessage("  §f/ksl $name §8— §7$description") }
    }

    // Runs work off the main thread and hands the result back on it: sendMessage and the Bukkit API belong there.
    private fun <T> background(sender: CommandSender, work: () -> T, done: (T) -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val outcome = runCatching(work)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                outcome
                    .onSuccess { done(it) }
                    .onFailure {
                        sender.sendMessage("§cОшибка: ${it::class.simpleName}: ${it.message}")
                        plugin.logger.warning("${KSLError.BACKGROUND_COMMAND_FAILED.tag} Фоновая команда /ksl не удалась: ${it::class.simpleName}: ${it.message}")
                    }
            })
        })
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<String>
    ): List<String> {
        if (!sender.hasPermission("ksl.admin")) return emptyList()
        if (args.size == 1)
            return listOf("help", "reload", "addons", "services", "libraries", "discord", "sandbox", "errors", "error", "doctor", "validate", "dbmigrate")
                .filter { it.startsWith(args[0].lowercase()) }
        if (args.size == 2 && args[0].equals("reload", ignoreCase = true)) {
            val ids = plugin.scriptIds()
            // Scripts, folders ("economy/") and the "changed" keyword.
            val folders = ids.filter { it.contains('/') }.map { it.substringBeforeLast('/') + "/" }.toSet()
            return (ids + folders + "changed").sorted().filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("error", ignoreCase = true)) {
            return KSLError.entries.map { it.code }.filter { it.startsWith(args[1].lowercase()) }.take(40)
        }
        if (args.size == 2 && args[0].equals("dbmigrate", ignoreCase = true)) {
            return listOf("confirm").filter { it.startsWith(args[1].lowercase()) }
        }
        return emptyList()
    }
}
