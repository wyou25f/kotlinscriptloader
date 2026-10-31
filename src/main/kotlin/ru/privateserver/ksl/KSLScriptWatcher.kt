package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.ConcurrentHashMap

// Development convenience (scripts.auto-reload): reloads exactly the script whose file changed.
// The watcher thread only records what changed; every reload happens on the main thread.
class KSLScriptWatcher(private val plugin: KotlinScriptLoaderPlugin) {

    private companion object {
        // Editors save in several steps; wait until the file has been quiet for this long.
        const val QUIET_MS = 500L
    }

    private val pending = ConcurrentHashMap<String, Long>()
    private val watched = ConcurrentHashMap<WatchKey, Path>()
    private var service: WatchService? = null
    private var thread: Thread? = null
    private var flushTask: BukkitTask? = null

    fun start() {
        val watchService = FileSystems.getDefault().newWatchService()
        service = watchService
        registerTree(watchService, plugin.scriptsFolder)
        thread = Thread({ pump(watchService) }, "KSL-Watcher").apply { isDaemon = true; start() }
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { flush() }, 20L, 10L)
    }

    fun stop() {
        flushTask?.cancel()
        runCatching { service?.close() }
        thread?.interrupt()
    }

    private fun isIgnored(dir: File): Boolean = dir.name.startsWith(".") || dir.name.startsWith("_")

    private fun registerTree(watchService: WatchService, root: File) {
        root.walkTopDown()
            .onEnter { it == plugin.scriptsFolder || !isIgnored(it) }
            .filter { it.isDirectory }
            .forEach { dir ->
                val key = dir.toPath().register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                watched[key] = dir.toPath()
            }
    }

    private fun pump(watchService: WatchService) {
        try {
            while (true) {
                val key = watchService.take()
                val dir = watched[key]
                if (dir != null) {
                    for (event in key.pollEvents()) {
                        if (event.kind() == OVERFLOW) continue
                        val child = dir.resolve(event.context() as Path).toFile()
                        when {
                            event.kind() == ENTRY_CREATE && child.isDirectory && !isIgnored(child) -> {
                                // A folder copied in with files already inside: watch it and queue what it contains.
                                registerTree(watchService, child)
                                child.walkTopDown().filter { it.isFile }.forEach { record(it) }
                            }
                            else -> record(child)
                        }
                    }
                }
                if (!key.reset()) watched.remove(key)
            }
        } catch (ex: ClosedWatchServiceException) {
        } catch (ex: InterruptedException) {
        }
    }

    private fun record(file: File) {
        if (file.extension != "kts" || file.name.startsWith(".")) return
        pending[plugin.scriptId(file)] = System.currentTimeMillis()
    }

    // Runs on the main thread twice a second.
    private fun flush() {
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val ready = pending.filterValues { now - it >= QUIET_MS }.keys.sorted()
        ready.forEach { id ->
            pending.remove(id)
            if (File(plugin.scriptsFolder, "$id.kts").isFile) {
                plugin.logger.info("[$id] Файл изменён — перезагружаю только его")
                plugin.reloadScript(id)
            } else {
                plugin.logger.info("[$id] Файл удалён — скрипт выгружен")
                plugin.removeScript(id)
            }
        }
    }
}
