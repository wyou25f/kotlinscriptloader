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
                val busy = "§eПерезагрузка уже выполняется, подожди пару секунд."
                // Compiling happens on a worker thread, so the server keeps ticking; the swap itself takes a moment on the main thread.
                when {
                    target == null -> {
                        sender.sendMessage("§7Перезагружаю все скрипты: компиляция идёт в фоне, сервер не блокируется...")
                        plugin.reloadScriptsAsync { result ->
                            if (result == null) {
                                sender.sendMessage(busy)
                            } else {
                                val color = if (result.second == 0) "§a" else "§e"
                                sender.sendMessage("${color}KSL: ${result.first} загружено, ${result.second} с ошибками.")
                            }
                        }
                    }
                    target.equals("changed", ignoreCase = true) -> {
                        plugin.reloadChangedAsync { report ->
                            if (report == null) {
                                sender.sendMessage(busy)
                            } else {
                                val color = if (report.failed == 0) "§a" else "§e"
                                sender.sendMessage("${color}KSL: перезагружено ${report.reloaded}, выгружено ${report.unloaded}, без изменений ${report.unchanged}, с ошибками ${report.failed}.")
                            }
                        }
                    }
                    target.endsWith("/") || File(plugin.scriptsFolder, target).isDirectory -> {
                        val started = plugin.reloadFolderAsync(target) { result ->
                            if (result == null) {
                                sender.sendMessage(busy)
                            } else {
                                val color = if (result.second == 0) "§a" else "§e"
                                sender.sendMessage("${color}KSL: папка '$target': ${result.first} загружено, ${result.second} с ошибками.")
                            }
                        }
                        if (started) sender.sendMessage("§7Перезагружаю папку '$target'...")
                        else sender.sendMessage("§cВ папке '$target' нет скриптов.")
                    }
                    else -> {
                        sender.sendMessage("§7Перезагружаю '$target.kts'...")
                        plugin.reloadScriptAsync(target) { ok ->
                            when (ok) {
                                null -> sender.sendMessage(busy)
                                true -> sender.sendMessage("§aKSL: '$target.kts' перезагружен, остальные скрипты не тронуты.")
                                false -> sender.sendMessage("§cНе удалось перезагрузить '$target.kts': см. консоль. Если он уже работал, продолжает работать прежняя версия.")
                            }
                        }
                    }
                }
            }

            "runtime", "perf", "memory", "cpu" -> {
                if (args.getOrNull(1)?.equals("workers", true) == true) {
                    val requested = args.getOrNull(2)?.toIntOrNull()
                    if (requested == null || requested !in 1..64) sender.sendMessage("§eИспользование: /ksl runtime workers <1-64>")
                    else sender.sendMessage("§aKSL: worker pool теперь использует ${plugin.resizeWorkers(requested)} поток(ов). Сохранено в config.yml.")
                } else {
                    val m = plugin.runtimeSnapshot()
                    val w = plugin.workScheduler.snapshot()
                    fun mib(bytes: Long) = if (bytes < 0L) "не задано" else "%.1f MiB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
                    fun percent(value: Double?) = value?.let { "%.1f%%".format(java.util.Locale.ROOT, it * 100.0) } ?: "н/д"
                    sender.sendMessage("§9§lKSL Runtime")
                    sender.sendMessage("§7Heap: §f${mib(m.heapUsedBytes)} §7/ §f${mib(m.heapMaxBytes)} §8| committed: ${mib(m.heapCommittedBytes)}")
                    sender.sendMessage("§7Non-heap: §f${mib(m.nonHeapUsedBytes)} §8| §7CPU: §f${m.processors} §8| process: §f${percent(m.processCpuLoad)} §8| system: §f${percent(m.systemCpuLoad)}")
                    sender.sendMessage("§7JVM threads: §f${m.liveThreads} §8(daemon: ${m.daemonThreads}) §7| GC: §f${m.gcCount} §8(${m.gcTimeMillis}мс)")
                    sender.sendMessage("§bWorker pool §8— §f${w.workers} поток(ов), active=${w.active}, queued=${w.queued}, tracked=${w.tracked}")
                    sender.sendMessage("§7Completed: §f${w.completed} §8| §7rejected: §f${w.rejected}")
                    sender.sendMessage("§7Настройка: §f/ksl runtime workers <1-64>")
                    sender.sendMessage("§8Размер heap задаётся JVM через -Xms/-Xmx; принудительный GC KSL не вызывает.")
                }
            }

            "scripts" -> {
                val infos = plugin.scriptInfos()
                if (infos.isEmpty()) {
                    sender.sendMessage("§eKSL: в scripts/ нет скриптов.")
                } else {
                    sender.sendMessage("§9§lСкрипты §7(${infos.count { it.loaded }} из ${infos.size} загружено):")
                    infos.forEach { info ->
                        val mark = if (info.loaded) "§a✔" else "§c✘"
                        val time = if (info.loadMillis >= 0) "${info.loadMillis}мс" else "—"
                        val errors = if (info.errors > 0) " §c${info.errors} ошибок" else ""
                        sender.sendMessage("  $mark §f${info.id} §8— §7$time$errors")
                    }
                }
            }

            "debug" -> {
                val target = args.getOrNull(1)?.removeSuffix(".kts")
                if (target.isNullOrBlank()) {
                    sender.sendMessage("§eИспользование: /ksl debug <скрипт>")
                } else {
                    val info = plugin.scriptInfos().firstOrNull { it.id.equals(target, ignoreCase = true) }
                    if (info == null) {
                        sender.sendMessage("§cСкрипт '$target' не найден.")
                    } else {
                        val resources = plugin.resourceSnapshot(info.id)
                        sender.sendMessage("§9§lKSL Debug §7— §f${info.id}")
                        sender.sendMessage("§7Состояние: ${if (info.loaded) "§aзагружен" else "§cне загружен"} §8| §7загрузка: ${if (info.loadMillis >= 0) "${info.loadMillis}мс" else "—"} §8| §7ошибки: ${info.errors}")
                        sender.sendMessage("§7Ресурсы: listeners=${resources.listeners}, tasks=${resources.tasks}, commands=${resources.commands}, placeholders=${resources.placeholders + resources.textPlaceholders}, GUI=${if (resources.hasGui) 1 else 0}, entities=${if (resources.hasEntities) "есть" else "нет"}")
                        val metrics = plugin.executionSnapshots(info.id)
                        if (metrics.isEmpty()) {
                            sender.sendMessage("§7Вызовы KSL-managed callbacks ещё не измерялись.")
                        } else {
                            sender.sendMessage("§bВыполнение (суммарное / среднее / максимум):")
                            metrics.take(12).forEach { metric ->
                                sender.sendMessage("  §f${metric.operation} §7— ${metric.calls} выз., ${metric.failures} ошибок, ${"%.2f".format(metric.totalNanos / 1_000_000.0)}мс / ${"%.2f".format(metric.averageNanos / 1_000_000.0)}мс / ${"%.2f".format(metric.maxNanos / 1_000_000.0)}мс")
                                metric.lastFailure?.let { sender.sendMessage("    §cПоследняя ошибка: $it") }
                            }
                        }
                    }
                }
            }

            "resources" -> {
                val target = args.getOrNull(1)?.removeSuffix(".kts")
                val snapshots = if (target.isNullOrBlank()) plugin.resourceSnapshots()
                    else plugin.resourceSnapshots().filter { it.scriptId.equals(target, ignoreCase = true) }
                if (snapshots.isEmpty()) {
                    sender.sendMessage("§eKSL: ресурсы скриптов не найдены.")
                } else {
                    sender.sendMessage("§9§lKSL Resources §7— ${snapshots.size} скрипт(ов)")
                    snapshots.forEach { item ->
                        val color = if (item.suspectedLeak) "§c" else if (item.loaded) "§a" else "§7"
                        sender.sendMessage("$color${item.scriptId} §8— §7listeners=${item.listeners}, tasks=${item.tasks}, commands=${item.commands}, placeholders=${item.placeholders + item.textPlaceholders}, GUI=${if (item.hasGui) 1 else 0}, entities=${if (item.hasEntities) 1 else 0}")
                        if (item.suspectedLeak) sender.sendMessage("  §cВНИМАНИЕ: у выгруженного скрипта остались отслеживаемые ресурсы.")
                    }
                }
            }

            "deps" -> {
                val target = args.getOrNull(1)?.removeSuffix(".kts")
                val dependencies = plugin.scriptDependencies().let { all ->
                    if (target.isNullOrBlank()) all else all.filter { it.scriptId.equals(target, ignoreCase = true) }
                }
                if (dependencies.isEmpty()) {
                    sender.sendMessage("§eKSL: скрипты или зависимости не найдены.")
                } else {
                    sender.sendMessage("§9§lKSL Dependencies §7— @requires")
                    dependencies.forEach { item ->
                        sender.sendMessage("§f${item.scriptId}")
                        if (item.declared.isEmpty()) sender.sendMessage("  §7зависимостей нет")
                        item.resolved.forEach { sender.sendMessage("  §a→ $it") }
                        item.missing.forEach { sender.sendMessage("  §c→ $it (не найдено)") }
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
                    KSLReport.compactErrors(KSLErrors.lastDetails, 8).forEach { sender.sendMessage("  §c$it") }
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
                background(sender, { plugin.selfTestCompiler() to plugin.sandboxProbe() }) { (compiler, sandboxProbe) ->
                    val lines = plugin.runDiagnostics(compiler, sandboxProbe)
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
                    val level = if (plugin.sandboxStrict) "strict: текстовый фильтр + запрет классов" else "text: только текстовый фильтр"
                    sender.sendMessage("§7Уровень: §f$level")
                    if (plugin.sandboxTrusted.isNotEmpty()) sender.sendMessage("§7Доверенные скрипты: §f${plugin.sandboxTrusted.sorted().joinToString(", ")}")
                    sender.sendMessage("§7Скрипт в sandbox не может загрузить Runtime, ProcessBuilder, рефлексию и ClassLoader; проверить это на сервере: §f/ksl doctor")
                    sender.sendMessage("§7Sandbox ограничивает опасные возможности JVM, но не права самого KSL: скрипту по-прежнему доступны Bukkit и DSL. Подробнее: docs/Security-and-Sandbox.md")
                }
                if (plugin.consoleBlocklist.isNotEmpty()) {
                    sender.sendMessage("§7Заблокированные консольные команды для скриптов: §f${plugin.consoleBlocklist.sorted().joinToString(", ")}")
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
            "reload [скрипт|папка/|changed]" to "перезагрузить всё, один скрипт, папку или только изменённые (в фоне)",
            "runtime" to "память JVM, CPU, потоки, очереди и планировщик",
            "scripts" to "список скриптов: статус, время загрузки, ошибки",
            "debug <скрипт>" to "диагностика выполнения, ошибок и ресурсов скрипта",
            "resources [скрипт]" to "отслеживаемые ресурсы и подозрительные остатки",
            "deps [скрипт]" to "граф зависимостей @requires",
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
            return listOf("help", "reload", "scripts", "debug", "resources", "deps", "runtime", "perf", "memory", "cpu", "addons", "services", "libraries", "discord", "sandbox", "errors", "error", "doctor", "validate", "dbmigrate")
                .filter { it.startsWith(args[0].lowercase()) }
        if (args.size == 2 && args[0].lowercase() in setOf("runtime", "perf", "memory", "cpu")) {
            return listOf("workers").filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size == 3 && args[0].lowercase() in setOf("runtime", "perf", "memory", "cpu") && args[1].equals("workers", true)) {
            return (1..64).map(Int::toString).filter { it.startsWith(args[2]) }
        }
        if (args.size == 2 && args[0].equals("reload", ignoreCase = true)) {
            val ids = plugin.scriptIds()
            // Scripts, folders ("economy/") and the "changed" keyword.
            val folders = ids.filter { it.contains('/') }.map { it.substringBeforeLast('/') + "/" }.toSet()
            return (ids + folders + "changed").sorted().filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].lowercase() in setOf("debug", "resources", "deps")) {
            return plugin.scriptIds().filter { it.startsWith(args[1].removeSuffix(".kts"), ignoreCase = true) }
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
