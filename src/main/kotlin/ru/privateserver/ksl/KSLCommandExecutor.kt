package ru.privateserver.ksl

import org.bukkit.Bukkit
import java.io.File
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

private val KSL_ROOT_COMMANDS = listOf(
    "help", "reload", "scripts", "debug", "resources", "deps", "runtime", "perf", "language",
    "memory", "cpu", "addons", "services", "libraries", "discord", "sandbox",
    "errors", "error", "watchdog", "doctor", "validate", "dbmigrate"
)
private val KSL_WORKER_COMMANDS = setOf("runtime", "perf", "memory", "cpu")
private val KSL_SCRIPT_COMMANDS = setOf("debug", "resources", "deps")
private val KSL_WORKER_COMPLETIONS = listOf("workers")
private val KSL_WORKER_COUNTS = (1..64).map(Int::toString)
private val KSL_ERROR_LIST_COMPLETIONS = listOf("list")
private val KSL_ERROR_CATEGORY_COMPLETIONS =
    listOf("all") + KSLErrorCategory.entries.map { it.letter.toString() }
private val KSL_ERROR_CODE_COMPLETIONS = KSLError.entries.map { it.code }
private val KSL_DB_MIGRATE_COMPLETIONS = listOf("confirm")

class KSLCommandExecutor(private val plugin: KotlinScriptLoaderPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("ksl.admin")) {
            sender.sendMessage(plugin.message("command.no-permission"))
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            null, "help" -> sendHelp(sender)

            "language" -> {
                val requested = args.getOrNull(1)
                if (requested == null) {
                    sender.sendMessage(plugin.message("language.current", "language" to plugin.localeManager.activeLanguage))
                    sender.sendMessage(plugin.message("language.available", "languages" to plugin.availableLanguages().joinToString(", ")))
                    sender.sendMessage(plugin.message("language.usage"))
                } else if (plugin.selectLanguage(requested)) {
                    sender.sendMessage(plugin.message("language.changed", "language" to plugin.localeManager.activeLanguage))
                } else {
                    sender.sendMessage(plugin.message("language.invalid", "language" to requested, "available" to plugin.availableLanguages().joinToString(", ")))
                }
            }

            "reload" -> {
                val target = args.getOrNull(1)
                val busy = plugin.message("reload.busy")
                // Compiling happens on a worker thread, so the server keeps ticking; the swap itself takes a moment on the main thread.
                when {
                    target == null -> {
                        sender.sendMessage(plugin.message("reload.all.start"))
                        plugin.reloadScriptsAsync { result ->
                            if (result == null) {
                                sender.sendMessage(busy)
                            } else {
                                val color = if (result.second == 0) "§a" else "§e"
                                sender.sendMessage(plugin.message("reload.all.done", "color" to color, "loaded" to result.first, "failed" to result.second))
                            }
                        }
                    }
                    target.equals("changed", ignoreCase = true) -> {
                        plugin.reloadChangedAsync { report ->
                            if (report == null) {
                                sender.sendMessage(busy)
                            } else {
                                val color = if (report.failed == 0) "§a" else "§e"
                                sender.sendMessage(plugin.message("reload.changed.done", "color" to color, "reloaded" to report.reloaded, "unloaded" to report.unloaded, "unchanged" to report.unchanged, "failed" to report.failed))
                            }
                        }
                    }
                    target.endsWith("/") || File(plugin.scriptsFolder, target).isDirectory -> {
                        val started = plugin.reloadFolderAsync(target) { result ->
                            if (result == null) {
                                sender.sendMessage(busy)
                            } else {
                                val color = if (result.second == 0) "§a" else "§e"
                                sender.sendMessage(plugin.message("reload.folder.done", "color" to color, "folder" to target, "loaded" to result.first, "failed" to result.second))
                            }
                        }
                        if (started) sender.sendMessage(plugin.message("reload.folder.start", "folder" to target))
                        else sender.sendMessage(plugin.message("reload.folder.empty", "folder" to target))
                    }
                    else -> {
                        sender.sendMessage(plugin.message("reload.script.start", "script" to target))
                        plugin.reloadScriptAsync(target) { ok ->
                            when (ok) {
                                null -> sender.sendMessage(busy)
                                true -> sender.sendMessage(plugin.message("reload.script.done", "script" to target))
                                false -> sender.sendMessage(plugin.message("reload.script.failed", "script" to target))
                            }
                        }
                    }
                }
            }

            "runtime", "perf", "memory", "cpu" -> {
                if (args.getOrNull(1)?.equals("workers", true) == true) {
                    val requested = args.getOrNull(2)?.toIntOrNull()
                    if (requested == null || requested !in 1..64) sender.sendMessage(plugin.message("runtime.workers.usage"))
                    else sender.sendMessage(plugin.message("runtime.workers.changed", "count" to plugin.resizeWorkers(requested)))
                } else {
                    val m = plugin.runtimeSnapshot()
                    val w = plugin.workScheduler.snapshot()
                    fun mib(bytes: Long) = if (bytes < 0L) "не задано" else "%.1f MiB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
                    fun percent(value: Double?) = value?.let { "%.1f%%".format(java.util.Locale.ROOT, it * 100.0) } ?: "н/д"
                    sender.sendMessage(plugin.message("runtime.title"))
                    sender.sendMessage(plugin.message("runtime.heap", "used" to mib(m.heapUsedBytes), "max" to mib(m.heapMaxBytes), "committed" to mib(m.heapCommittedBytes)))
                    sender.sendMessage(plugin.message("runtime.nonheap", "nonheap" to mib(m.nonHeapUsedBytes), "processors" to m.processors, "process" to percent(m.processCpuLoad), "system" to percent(m.systemCpuLoad)))
                    sender.sendMessage(plugin.message("runtime.threads", "threads" to m.liveThreads, "daemon" to m.daemonThreads, "gc" to m.gcCount, "gc-ms" to m.gcTimeMillis))
                    sender.sendMessage(plugin.message("runtime.pool", "workers" to w.workers, "active" to w.active, "queued" to w.queued, "tracked" to w.tracked))
                    sender.sendMessage(plugin.message("runtime.tasks", "completed" to w.completed, "rejected" to w.rejected))
                    sender.sendMessage("§7Compile cache: ${plugin.compileCacheSummary()}")
                    sender.sendMessage(plugin.message("runtime.setting"))
                    sender.sendMessage(plugin.message("runtime.heap-note"))
                }
            }

            "scripts" -> {
                val infos = plugin.scriptInfos()
                if (infos.isEmpty()) {
                    sender.sendMessage(plugin.message("scripts.empty"))
                } else {
                    sender.sendMessage(plugin.message("scripts.title", "loaded" to infos.count { it.loaded }, "total" to infos.size))
                    infos.forEach { info ->
                        val mark = if (info.loaded) "§a✔" else "§c✘"
                        val time = if (info.loadMillis >= 0) "${info.loadMillis}мс" else "—"
                        val errors = if (info.errors > 0) plugin.message("scripts.error-count", "count" to info.errors) else ""
                        sender.sendMessage(plugin.message("scripts.entry", "mark" to mark, "name" to info.id, "time" to time, "errors" to errors))
                    }
                }
            }

            "debug" -> {
                val target = args.getOrNull(1)?.removeSuffix(".kts")
                if (target.isNullOrBlank()) {
                    sender.sendMessage(plugin.message("debug.usage"))
                } else {
                    val info = plugin.scriptInfos().firstOrNull { it.id.equals(target, ignoreCase = true) }
                    if (info == null) {
                        sender.sendMessage(plugin.message("debug.not-found", "script" to target))
                    } else {
                        val resources = plugin.resourceSnapshot(info.id)
                        sender.sendMessage(plugin.message("debug.title", "script" to info.id))
                        sender.sendMessage(plugin.message("debug.state", "state" to plugin.message(if (info.loaded) "debug.state-loaded" else "debug.state-unloaded"), "time" to if (info.loadMillis >= 0) "${info.loadMillis}ms" else "—", "errors" to info.errors))
                        sender.sendMessage(plugin.message("debug.resources", "listeners" to resources.listeners, "tasks" to resources.tasks, "commands" to resources.commands, "placeholders" to (resources.placeholders + resources.textPlaceholders), "gui" to if (resources.hasGui) 1 else 0, "entities" to plugin.message(if (resources.hasEntities) "debug.yes" else "debug.no")))
                        val metrics = plugin.executionSnapshots(info.id)
                        if (metrics.isEmpty()) {
                            sender.sendMessage(plugin.message("debug.not-measured"))
                        } else {
                            sender.sendMessage(plugin.message("debug.metrics-title"))
                            metrics.take(12).forEach { metric ->
                                sender.sendMessage(plugin.message("debug.metric", "operation" to metric.operation, "calls" to metric.calls, "failures" to metric.failures, "total" to "%.2f".format(java.util.Locale.ROOT, metric.totalNanos / 1_000_000.0), "average" to "%.2f".format(java.util.Locale.ROOT, metric.averageNanos / 1_000_000.0), "maximum" to "%.2f".format(java.util.Locale.ROOT, metric.maxNanos / 1_000_000.0)))
                                metric.lastFailure?.let { sender.sendMessage(plugin.message("debug.last-failure", "failure" to it)) }
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
                    sender.sendMessage(plugin.message("resources.empty"))
                } else {
                    sender.sendMessage(plugin.message("resources.title", "count" to snapshots.size))
                    snapshots.forEach { item ->
                        val color = if (item.suspectedLeak) "§c" else if (item.loaded) "§a" else "§7"
                        sender.sendMessage(plugin.message("resources.entry", "color" to color, "script" to item.scriptId, "listeners" to item.listeners, "tasks" to item.tasks, "commands" to item.commands, "placeholders" to (item.placeholders + item.textPlaceholders), "gui" to if (item.hasGui) 1 else 0, "entities" to if (item.hasEntities) 1 else 0))
                        if (item.suspectedLeak) sender.sendMessage(plugin.message("resources.leak"))
                    }
                }
            }

            "deps" -> {
                val target = args.getOrNull(1)?.removeSuffix(".kts")
                val dependencies = plugin.scriptDependencies().let { all ->
                    if (target.isNullOrBlank()) all else all.filter { it.scriptId.equals(target, ignoreCase = true) }
                }
                if (dependencies.isEmpty()) {
                    sender.sendMessage(plugin.message("deps.empty"))
                } else {
                    sender.sendMessage(plugin.message("deps.title"))
                    dependencies.forEach { item ->
                        sender.sendMessage("§f${item.scriptId}")
                        if (item.declared.isEmpty()) sender.sendMessage(plugin.message("deps.none"))
                        item.resolved.forEach { sender.sendMessage("  §a→ $it") }
                        item.missing.forEach { sender.sendMessage(plugin.message("deps.missing", "dependency" to it)) }
                    }
                }
            }

            "addons" -> {
                val addons = plugin.addonManager.registeredAddons()
                if (addons.isEmpty()) {
                    sender.sendMessage(plugin.message("addons.empty"))
                } else {
                    sender.sendMessage(plugin.message("addons.title", "count" to addons.size))
                    addons.forEach { addon ->
                        val desc = if (addon.addonDescription.isNotBlank()) " §8— ${addon.addonDescription}" else ""
                        sender.sendMessage("  §f${addon.addonId} §7v${addon.addonVersion}$desc")
                        if (addon.dependsOn.isNotEmpty()) {
                            val deps = addon.dependsOn.joinToString(", ") { dep ->
                                if (plugin.addonManager.isAddonLoaded(dep)) "§a$dep§7" else "§c$dep (нет)§7"
                            }
                            sender.sendMessage(plugin.message("addons.dependency", "dependencies" to deps))
                        }
                    }
                }
            }

            "services" -> {
                val keys = plugin.addonManager.getServiceKeys()
                if (keys.isEmpty()) {
                    sender.sendMessage(plugin.message("services.empty"))
                } else {
                    sender.sendMessage(plugin.message("services.title", "count" to keys.size))
                    keys.sorted().forEach { sender.sendMessage("  §f$it") }
                }
            }

            "discord" -> {
                val discord = plugin.discord
                if (discord == null) {
                    sender.sendMessage(plugin.message("discord.empty"))
                } else {
                    val channels = discord.channels()
                    sender.sendMessage(plugin.message("discord.channels", "count" to channels.size, "channels" to channels.joinToString(", ")))
                }
            }

            "libraries" -> {
                val keys = plugin.libraryRegistry.exportedKeys()
                if (keys.isEmpty()) {
                    sender.sendMessage(plugin.message("libraries.empty"))
                } else {
                    sender.sendMessage(plugin.message("libraries.title", "count" to keys.size))
                    keys.sorted().forEach { key ->
                        val owner = plugin.libraryRegistry.ownerOf(key) ?: "?"
                        sender.sendMessage(plugin.message("libraries.owner", "library" to key, "owner" to owner))
                    }
                }
            }

            "errors" -> {
                if (args.getOrNull(1)?.equals("list", ignoreCase = true) == true) {
                    val categoryArg = args.getOrNull(2)?.takeUnless { it.equals("all", ignoreCase = true) }
                    val category = categoryArg?.singleOrNull()?.let { KSLErrorCategory.byLetter(it) }
                    if (categoryArg != null && category == null) {
                        sender.sendMessage(plugin.message("errors.unknown-category", "category" to categoryArg))
                    } else {
                        val entries = KSLError.entries.filter { category == null || it.category == category }.sortedBy { it.code }
                        val pageSize = 20
                        val pages = ((entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                        val page = (args.getOrNull(3)?.toIntOrNull() ?: 1).coerceIn(1, pages)
                        sender.sendMessage(plugin.message("errors.catalog-title", "category" to (category?.let { plugin.message("error-codes.categories.${it.letter}") } ?: plugin.message("errors.catalog-all")), "count" to entries.size, "page" to page, "pages" to pages))
                        entries.drop((page - 1) * pageSize).take(pageSize).forEach { entry ->
                            val title = plugin.message("error-codes.entries.${entry.code}.title")
                            sender.sendMessage("  §e${entry.code} §8— §f$title")
                        }
                        sender.sendMessage(plugin.message("errors.catalog-next", "category" to (category?.letter ?: "all"), "page" to (page + 1)))
                    }
                } else {
                    val total = KSLErrors.totalCount()
                    val last = KSLErrors.lastError()
                    sender.sendMessage(plugin.message("errors.summary", "total" to total, "count" to KSLError.entries.size))
                    sender.sendMessage(plugin.message("errors.catalog-command"))
                    if (last != null) {
                        val ago = (System.currentTimeMillis() - last.timestamp) / 1000
                        sender.sendMessage(plugin.message("errors.last", "script" to last.scriptName, "where" to last.where, "seconds" to ago))
                        sender.sendMessage("§7${last.message}")
                        sender.sendMessage(plugin.message("errors.code", "code" to last.code.code, "title" to plugin.message("error-codes.entries.${last.code.code}.title")))
                        KSLReport.compactErrors(KSLErrors.lastDetails, 8).forEach { sender.sendMessage("  §c$it") }
                    } else {
                        sender.sendMessage(plugin.message("errors.none"))
                    }
                }
            }
            "error" -> {
                val query = args.getOrNull(1)
                if (query == null) {
                    sender.sendMessage(plugin.message("error.usage", "example" to KSLError.SCRIPT_COMPILE.code))
                    KSLErrorCategory.entries.forEach { category ->
                        val title = plugin.message("error-codes.categories.${category.letter}")
                        sender.sendMessage("  §e${category.letter} §8— §7$title")
                    }
                } else {
                    val error = KSLError.find(query)
                    if (error == null) {
                        sender.sendMessage(plugin.message("error.not-found", "code" to query))
                    } else {
                        sender.sendMessage(plugin.message("error.title", "code" to error.code, "title" to plugin.message("error-codes.entries.${error.code}.title"), "category" to plugin.message("error-codes.categories.${error.category.letter}")))
                        sender.sendMessage(plugin.message("error.hint", "hint" to plugin.message("error-codes.entries.${error.code}.hint")))
                    }
                }
            }

            "watchdog" -> {
                val monitor = plugin.watchdogMonitor
                val dump = monitor?.latestDump()
                if (monitor == null) {
                    sender.sendMessage(plugin.message("watchdog.disabled"))
                } else if (dump == null) {
                    sender.sendMessage(plugin.message("watchdog.active"))
                } else {
                    sender.sendMessage(plugin.message("watchdog.stall", "duration" to monitor.lastStallDurationMillis()))
                    sender.sendMessage(plugin.message("watchdog.dump", "path" to dump.relativeTo(plugin.dataFolder).path))
                    sender.sendMessage(plugin.message("watchdog.guide"))
                }
            }
            "validate" -> {
                sender.sendMessage(plugin.message("validate.start"))
                background(sender, { plugin.validateAllScripts() }) { results ->
                    val broken = results.filterValues { it.isNotEmpty() }
                    if (broken.isEmpty()) {
                        sender.sendMessage(plugin.message("validate.ok", "count" to results.size))
                    } else {
                        sender.sendMessage(plugin.message("validate.failed", "failed" to broken.size, "total" to results.size))
                        broken.forEach { (scriptName, issues) ->
                            sender.sendMessage("  §f$scriptName§7:")
                            issues.forEach { sender.sendMessage("    §c$it") }
                        }
                    }
                }
            }

            "doctor" -> {
                sender.sendMessage(plugin.message("doctor.title"))
                // The first Kotlin compile initialises the whole compiler; on the server thread that froze the tick for 10+ s.
                background(sender, { plugin.selfTestCompiler() to plugin.sandboxProbe() }) { (compiler, sandboxProbe) ->
                    val lines = plugin.runDiagnostics(compiler, sandboxProbe)
                    lines.forEach { line ->
                        val mark = if (line.ok) "§a✔" else "§c✘"
                        sender.sendMessage("$mark §f${line.label}§7: ${line.detail}")
                    }
                    val failedCount = lines.count { !it.ok }
                    if (failedCount == 0) {
                        sender.sendMessage(plugin.message("doctor.ok"))
                    } else {
                        sender.sendMessage(plugin.message("doctor.attention", "count" to failedCount))
                    }
                }
            }

            "dbmigrate" -> {
                if (!plugin.dialect.isRemote) {
                    sender.sendMessage(plugin.message("dbmigrate.sqlite"))
                    return true
                }
                val confirmed = args.getOrNull(1).equals("confirm", ignoreCase = true)
                sender.sendMessage(plugin.message("dbmigrate.read"))
                background(sender, {
                    val migrator = KSLDbMigrator(plugin)
                    val found = migrator.inspect()
                    if (found != null && confirmed) found to migrator.migrate() else found to null
                }) { (found, migrated) ->
                    when {
                        found == null -> sender.sendMessage(plugin.message("dbmigrate.not-found"))
                        migrated == null -> {
                            sender.sendMessage(plugin.message("dbmigrate.found", "persist" to found.persist, "table" to found.table))
                            sender.sendMessage(plugin.message("dbmigrate.copy", "database" to plugin.dialect.label))
                            sender.sendMessage(plugin.message("dbmigrate.confirm"))
                        }
                        else -> sender.sendMessage(plugin.message("dbmigrate.done", "persist" to migrated.persist, "table" to migrated.table))
                    }
                }
            }

            "sandbox" -> {
                sender.sendMessage(plugin.message(if (plugin.sandboxEnabled) "sandbox.enabled" else "sandbox.disabled"))
                if (plugin.sandboxEnabled) {
                    val level = plugin.message(if (plugin.sandboxStrict) "sandbox.level-strict" else "sandbox.level-text")
                    sender.sendMessage(plugin.message("sandbox.level", "level" to level))
                    if (plugin.sandboxTrusted.isNotEmpty()) sender.sendMessage(plugin.message("sandbox.trusted", "scripts" to plugin.sandboxTrusted.sorted().joinToString(", ")))
                    sender.sendMessage(plugin.message("sandbox.runtime"))
                    sender.sendMessage(plugin.message("sandbox.description"))
                }
                if (plugin.consoleBlocklist.isNotEmpty()) {
                    sender.sendMessage(plugin.message("sandbox.blocklist", "commands" to plugin.consoleBlocklist.sorted().joinToString(", ")))
                }
            }

            else -> sendHelp(sender, unknown = args[0])
        }
        return true
    }

    private fun sendHelp(sender: CommandSender, unknown: String? = null) {
        if (unknown != null) sender.sendMessage(plugin.message("help.unknown", "name" to unknown))
        sender.sendMessage(plugin.message("help.title"))
        listOf(
            "reload [script|folder/|changed]" to plugin.message("help.reload"),
            "runtime" to plugin.message("help.runtime"),
            "scripts" to plugin.message("help.scripts"),
            "debug <script>" to plugin.message("help.debug"),
            "resources [script]" to plugin.message("help.resources"),
            "deps [script]" to plugin.message("help.deps"),
            "addons" to plugin.message("help.addons"),
            "services" to plugin.message("help.services"),
            "libraries" to plugin.message("help.libraries"),
            "discord" to plugin.message("help.discord"),
            "sandbox" to plugin.message("help.sandbox"),
            "errors [list [category] [page]]" to plugin.message("help.errors"),
            "watchdog" to plugin.message("help.watchdog"),
            "error <code>" to plugin.message("help.error", "code" to KSLError.SCRIPT_COMPILE.code),
            "doctor" to plugin.message("help.doctor"),
            "validate" to plugin.message("help.validate"),
            "dbmigrate [confirm]" to plugin.message("help.dbmigrate"),
            "language [ru|en|custom]" to plugin.message("help.language")
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
                        sender.sendMessage(plugin.message("background.error", "type" to it::class.simpleName, "message" to it.message))
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
            return KSL_ROOT_COMMANDS.filter { it.startsWith(args[0], ignoreCase = true) }
        if (args.size == 2 && args[0].lowercase() in KSL_WORKER_COMMANDS) {
            return KSL_WORKER_COMPLETIONS.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 3 && args[0].lowercase() in KSL_WORKER_COMMANDS && args[1].equals("workers", true)) {
            return KSL_WORKER_COUNTS.filter { it.startsWith(args[2]) }
        }
        if (args.size == 2 && args[0].equals("language", ignoreCase = true)) {
            return plugin.availableLanguages().filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("reload", ignoreCase = true)) {
            return plugin.scriptReloadTargets().filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].lowercase() in KSL_SCRIPT_COMMANDS) {
            return plugin.scriptIds().filter { it.startsWith(args[1].removeSuffix(".kts"), ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("errors", ignoreCase = true)) {
            return KSL_ERROR_LIST_COMPLETIONS.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 3 && args[0].equals("errors", true) && args[1].equals("list", true)) {
            return KSL_ERROR_CATEGORY_COMPLETIONS.filter { it.startsWith(args[2], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("error", ignoreCase = true)) {
            return KSL_ERROR_CODE_COMPLETIONS.asSequence()
                .filter { it.startsWith(args[1], ignoreCase = true) }
                .take(40)
                .toList()
        }
        if (args.size == 2 && args[0].equals("dbmigrate", ignoreCase = true)) {
            return KSL_DB_MIGRATE_COMPLETIONS.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
