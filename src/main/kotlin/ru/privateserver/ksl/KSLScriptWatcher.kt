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
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    // Keep ids for files already loaded or observed: after a directory is deleted, its children are no longer on disk.
    private val knownScripts = ConcurrentHashMap.newKeySet<String>()
    private val watched = ConcurrentHashMap<WatchKey, Path>()
    private var service: WatchService? = null
    private var thread: Thread? = null
    private var flushTask: BukkitTask? = null

    @Synchronized
    fun start() {
        check(!stopped.get()) { "Script watcher has already been stopped" }
        if (service != null) return
        val watchService = FileSystems.getDefault().newWatchService()
        service = watchService
        knownScripts.addAll(plugin.scriptIds())
        registerTree(watchService, plugin.scriptsFolder)
        thread = Thread({ pump(watchService) }, "KSL-Watcher").apply { isDaemon = true; start() }
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { flush() }, 20L, 10L)
    }

    @Synchronized
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        flushTask?.cancel()
        flushTask = null
        runCatching { service?.close() }
        thread?.interrupt()
        if (Thread.currentThread() !== thread) {
            runCatching { thread?.join(1_000L) }
        }
        thread = null
        service = null
        watched.clear()
        pending.clear()
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
                        if (event.kind() == OVERFLOW) {
                            rescan()
                            continue
                        }
                        val child = dir.resolve(event.context() as Path).toFile()
                        when {
                            event.kind() == ENTRY_CREATE && child.isDirectory && !isIgnored(child) -> {
                                // A folder copied in with files already inside: watch it and queue what it contains.
                                registerTree(watchService, child)
                                child.walkTopDown().filter { it.isFile }.forEach { record(it) }
                            }
                            event.kind() == ENTRY_DELETE && !child.name.endsWith(".kts") -> recordDeletedDirectory(child)
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

    private fun rescan() {
        if (stopped.get()) return
        val current = plugin.scriptIds().toSet()
        val now = System.currentTimeMillis()
        (knownScripts - current).forEach { pending[it] = now }
        current.forEach { id -> pending[id] = now }
        knownScripts.clear()
        knownScripts.addAll(current)
    }

    private fun record(file: File) {
        if (stopped.get() || file.extension != "kts" || file.name.startsWith(".")) return
        val id = plugin.scriptId(file)
        knownScripts.add(id)
        pending[id] = System.currentTimeMillis()
    }

    // Deleting a directory produces one event for the directory, not one event per child file.
    // Use ids remembered before deletion so scripts from the removed subtree are unloaded too.
    private fun recordDeletedDirectory(directory: File) {
        if (stopped.get()) return
        val relative = runCatching {
            plugin.scriptsFolder.toPath().relativize(directory.toPath()).toString()
                .replace(File.separatorChar, '/').trimEnd('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val prefix = "$relative/"
        val now = System.currentTimeMillis()
        knownScripts.filter { it.startsWith(prefix) }.forEach { id -> pending[id] = now }
    }

    // Runs on the main thread twice a second.
    private fun flush() {
        if (stopped.get() || pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val ready = pending.filterValues { now - it >= QUIET_MS }.keys.sorted()
        ready.forEach { id ->
            pending.remove(id)
            if (File(plugin.scriptsFolder, "$id.kts").isFile) {
                knownScripts.add(id)
                plugin.logger.info("[$id] Файл изменён — перезагружаю только его")
                plugin.reloadScriptAsync(id) { result ->
                    if (result == null && !stopped.get()) pending[id] = System.currentTimeMillis()
                }
            } else {
                knownScripts.remove(id)
                plugin.logger.info("[$id] Файл удалён — скрипт выгружается")
                plugin.removeScriptAsync(id) { result ->
                    if (result == null && !stopped.get()) pending[id] = System.currentTimeMillis()
                }
            }
        }
    }
}
