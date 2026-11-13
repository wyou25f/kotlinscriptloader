package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures an early thread dump when the server's primary thread stops advancing.
 *
 * This is deliberately independent from Bukkit's scheduler: the monitor must keep running
 * while the primary thread is blocked. It does not disable or replace Paper's watchdog.
 */
internal class KSLWatchdogMonitor(
    private val plugin: KotlinScriptLoaderPlugin,
    thresholdSeconds: Long,
    pollIntervalMillis: Long,
    private val maxDumps: Int
) {
    private val thresholdNanos = TimeUnit.SECONDS.toNanos(thresholdSeconds.coerceIn(2L, 300L))
    private val pollMillis = pollIntervalMillis.coerceIn(100L, 5_000L)
    private val incidentActive = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    @Volatile private var mainThread: Thread? = null
    @Volatile private var activeOperation: String? = null
    @Volatile private var lastHeartbeatNanos: Long = System.nanoTime()
    @Volatile private var heartbeatTask: BukkitTask? = null

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "KSL-Watchdog-Diagnostics").apply { isDaemon = true }
        }

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())

    fun start() {
        if (stopped.get()) return
        mainThread = Thread.currentThread()
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { heartbeat() }, 1L, 1L)
        executor.scheduleAtFixedRate(
            { runCatching { checkHeartbeat() }.onFailure { error ->
                runCatching { plugin.logger.warning("[KSL Watchdog] Не удалось проверить heartbeat: ${error.message}") }
            } },
            pollMillis,
            pollMillis,
            TimeUnit.MILLISECONDS
        )
        plugin.logger.info("Диагностика Watchdog включена: дамп после ${TimeUnit.NANOSECONDS.toSeconds(thresholdNanos)}с без тиков")
    }

    private fun heartbeat() {
        lastHeartbeatNanos = System.nanoTime()
        incidentActive.set(false)
    }

    /**
     * Adds context only for work running on the server thread. Async KSL tasks must not
     * overwrite the label of the operation currently blocking the main thread.
     */
    fun <T> trackOperation(label: String, block: () -> T): T {
        if (Thread.currentThread() !== mainThread) return block()
        val previous = activeOperation
        activeOperation = label
        return try {
            block()
        } finally {
            activeOperation = previous
        }
    }

    private fun checkHeartbeat() {
        if (stopped.get()) return
        val now = System.nanoTime()
        val elapsed = now - lastHeartbeatNanos
        if (elapsed < thresholdNanos || !incidentActive.compareAndSet(false, true)) return
        writeDump(elapsed)
    }

    private fun writeDump(stallNanos: Long) {
        val detectedAt = Instant.now()
        val main = mainThread
        val allStacks = Thread.getAllStackTraces()
        val mainStack = main?.let { allStacks[it] ?: it.stackTrace } ?: emptyArray()
        val operation = activeOperation
        val likelyCause = when {
            operation != null -> "Активная операция KSL: $operation (это контекст выполнения, не доказательство первопричины)."
            mainStack.any { it.className.startsWith("ru.privateserver.ksl.") } ->
                "В стеке главного потока присутствуют классы KSL; проверь стек ниже."
            else -> "Активная операция KSL не зарегистрирована; проверь стек главного потока и другие плагины."
        }

        val report = buildString {
            appendLine("KSL early watchdog diagnostic")
            appendLine("Detected at: $detectedAt")
            appendLine("Stalled for: ${TimeUnit.NANOSECONDS.toMillis(stallNanos)} ms")
            appendLine("Threshold: ${TimeUnit.NANOSECONDS.toMillis(thresholdNanos)} ms")
            appendLine("KSL version: ${plugin.description.version}")
            appendLine("Java: ${System.getProperty("java.version")}")
            appendLine("Likely area: $likelyCause")
            appendLine("")

            appendLine("=== Server main thread ===")
            if (main == null) {
                appendLine("Main thread reference is unavailable.")
            } else {
                appendLine("Name: ${main.name}")
                appendLine("Id: ${main.id}")
                appendLine("State: ${main.state}")
                appendLine("Active KSL operation: ${operation ?: "none"}")
                mainStack.forEach { appendLine("\tat $it") }
            }

            appendLine("")
            appendLine("=== All JVM threads ===")
            allStacks.entries.sortedBy { it.key.name }.forEach { (thread, stack) ->
                appendLine("\"${thread.name}\" id=${thread.id} state=${thread.state} daemon=${thread.isDaemon}")
                stack.forEach { appendLine("\tat $it") }
                appendLine("")
            }
        }

        try {
            val directory = File(plugin.dataFolder, "dumps")
            Files.createDirectories(directory.toPath())
            val file = File(directory, "watchdog-${timestampFormat.format(detectedAt)}.txt")
            Files.writeString(
                file.toPath(),
                report,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            pruneOldDumps(directory)
            plugin.logger.warning("[KSL Watchdog] Главный поток не отвечал ${TimeUnit.NANOSECONDS.toMillis(stallNanos)}мс; дамп: ${file.relativeTo(plugin.dataFolder).path}")
        } catch (error: Throwable) {
            runCatching { plugin.logger.severe("[KSL Watchdog] Не удалось сохранить дамп: ${error.message}") }
        }
    }

    private fun pruneOldDumps(directory: File) {
        val keep = maxDumps.coerceIn(1, 50)
        val dumps = directory.listFiles { file ->
            file.isFile && file.name.startsWith("watchdog-") && file.name.endsWith(".txt")
        } ?: return
        dumps.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { heartbeatTask?.cancel() }
        executor.shutdownNow()
    }
}
