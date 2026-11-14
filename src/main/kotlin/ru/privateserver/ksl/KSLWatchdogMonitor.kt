package ru.privateserver.ksl

import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
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
 * Captures several bounded JVM thread snapshots when the server's primary thread stops advancing.
 * Runs independently from Bukkit's scheduler and never replaces/disables Paper's watchdog.
 */
internal class KSLWatchdogMonitor(
    private val plugin: KotlinScriptLoaderPlugin,
    thresholdSeconds: Long,
    pollIntervalMillis: Long,
    private val maxDumps: Int,
    private val snapshotCount: Int = 3,
    private val snapshotIntervalMillis: Long = 750L
) {
    private val thresholdNanos = TimeUnit.SECONDS.toNanos(thresholdSeconds.coerceIn(2L, 300L))
    private val pollMillis = pollIntervalMillis.coerceIn(100L, 5_000L)
    private val snapshotLimit = snapshotCount.coerceIn(1, 5)
    private val snapshotDelay = snapshotIntervalMillis.coerceIn(250L, 2_000L)
    private val incidentActive = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    @Volatile private var mainThread: Thread? = null
    @Volatile private var activeOperation: String? = null
    @Volatile private var lastHeartbeatNanos: Long = System.nanoTime()
    @Volatile private var heartbeatTask: org.bukkit.scheduler.BukkitTask? = null
    @Volatile private var lastDumpFile: File? = null
    @Volatile private var lastStallMillis: Long = 0L
    @Volatile private var incidentStartedAtNanos: Long = 0L
    @Volatile private var lastCapturedSnapshotCount: Int = 0

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "KSL-Watchdog-Diagnostics").apply { isDaemon = true }
        }

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault())

    fun start() {
        if (stopped.get()) return
        mainThread = Thread.currentThread()
        heartbeatTask = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, Runnable { heartbeat() }, 1L, 1L)
        executor.scheduleAtFixedRate(
            { runCatching { checkHeartbeat() }.onFailure { error ->
                runCatching {
                    plugin.logger.warning("${KSLError.SYSTEM_WATCHDOG_SNAPSHOT_FAILED.tag} Не удалось проверить heartbeat: ${error.message}")
                }
            } },
            pollMillis,
            pollMillis,
            TimeUnit.MILLISECONDS
        )
        plugin.logger.info("Диагностика Watchdog включена: дамп после ${TimeUnit.NANOSECONDS.toSeconds(thresholdNanos)}с без тиков")
    }

    private fun heartbeat() {
        val now = System.nanoTime()
        lastHeartbeatNanos = now
        if (incidentActive.compareAndSet(true, false)) {
            val startedAt = incidentStartedAtNanos
            if (startedAt > 0L) lastStallMillis = TimeUnit.NANOSECONDS.toMillis((now - startedAt).coerceAtLeast(0L))
            incidentStartedAtNanos = 0L
            val dumpPath = latestDump()?.let { runCatching { it.relativeTo(plugin.dataFolder).path }.getOrNull() } ?: "дамп не сохранён"
            plugin.logger.warning(
                "${KSLError.SYSTEM_WATCHDOG_RECOVERED.tag} Главный поток снова обрабатывает тики; " +
                    "зависание длилось около ${lastStallMillis}мс; снимков: ${lastCapturedSnapshotCount}; дамп: $dumpPath"
            )
        }
    }

    /** Adds context only for work running on the server thread. */
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
        val observedHeartbeat = lastHeartbeatNanos
        val elapsed = System.nanoTime() - observedHeartbeat
        if (elapsed < thresholdNanos) return
        incidentStartedAtNanos = observedHeartbeat
        if (!incidentActive.compareAndSet(false, true)) return
        if (lastHeartbeatNanos != observedHeartbeat) {
            incidentActive.compareAndSet(true, false)
            incidentStartedAtNanos = 0L
            return
        }
        lastStallMillis = TimeUnit.NANOSECONDS.toMillis(elapsed)
        writeDump(elapsed, observedHeartbeat)
    }

    private data class ThreadSnapshot(
        val capturedAt: Instant,
        val threads: List<Pair<Thread, Array<StackTraceElement>>>,
        val infos: Map<Long, ThreadInfo>,
        val cpuTimesNanos: Map<Long, Long>,
        val deadlockedIds: Set<Long>,
        val operation: String?,
        val totalThreadCount: Int
    )

    private fun captureSnapshot(): ThreadSnapshot {
        val capturedAt = Instant.now()
        val allStacks = Thread.getAllStackTraces()
        val main = mainThread
        val entries = allStacks.entries.sortedWith(
            compareBy<Map.Entry<Thread, Array<StackTraceElement>>> {
                when {
                    it.key === main -> 0
                    it.key.name.startsWith("KSL") -> 1
                    else -> 2
                }
            }.thenBy { it.key.name }
        )
        val limited = entries.take(MAX_THREADS).map { it.key to it.value.take(MAX_FRAMES).toTypedArray() }
        val bean = ManagementFactory.getThreadMXBean()
        val ids = limited.map { it.first.id }.toLongArray()
        val infos = runCatching {
            bean.getThreadInfo(ids, true, true).filterNotNull().associateBy { it.threadId }
        }.getOrDefault(emptyMap())
        val cpuTimes = runCatching {
            if (bean.isThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled) {
                ids.mapNotNull { id ->
                    val time = bean.getThreadCpuTime(id)
                    if (time >= 0L) id to time else null
                }.toMap()
            } else emptyMap()
        }.getOrDefault(emptyMap())
        val deadlocked = runCatching { bean.findDeadlockedThreads()?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
        return ThreadSnapshot(capturedAt, limited, infos, cpuTimes, deadlocked, activeOperation, allStacks.size)
    }

    private fun writeDump(stallNanos: Long, observedHeartbeat: Long) {
        val detectedAt = Instant.now()
        val captured = mutableListOf<ThreadSnapshot>()
        for (index in 0 until snapshotLimit) {
            if (stopped.get() && index > 0) break
            if (index > 0 && lastHeartbeatNanos != observedHeartbeat) break
            runCatching { captureSnapshot() }
                .onSuccess { captured += it }
                .onFailure { error ->
                    runCatching {
                        plugin.logger.warning("${KSLError.SYSTEM_WATCHDOG_SNAPSHOT_FAILED.tag} Снимок ${index + 1}/$snapshotLimit не получен: ${error.message}")
                    }
                }
            if (index < snapshotLimit - 1 && !stopped.get() && lastHeartbeatNanos == observedHeartbeat) {
                try {
                    Thread.sleep(snapshotDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        lastCapturedSnapshotCount = captured.size
        val recoveredDuringCapture = lastHeartbeatNanos != observedHeartbeat
        if (recoveredDuringCapture) {
            lastStallMillis = TimeUnit.NANOSECONDS.toMillis((lastHeartbeatNanos - observedHeartbeat).coerceAtLeast(0L))
        } else {
            lastStallMillis = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - observedHeartbeat).coerceAtLeast(0L))
        }
        try {
            val directory = File(plugin.dataFolder, "dumps")
            Files.createDirectories(directory.toPath())
            val file = File(directory, "watchdog-${timestampFormat.format(detectedAt)}-${System.nanoTime()}.txt")
            val report = buildReport(detectedAt, stallNanos, captured, allThreadCount = captured.maxOfOrNull { it.totalThreadCount } ?: 0, recoveredDuringCapture = recoveredDuringCapture)
            Files.writeString(
                file.toPath(),
                report,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )
            lastDumpFile = file
            pruneOldDumps(directory)
            plugin.logger.warning(
                "${KSLError.SYSTEM_WATCHDOG_STALL.tag} Главный поток не отвечал ${TimeUnit.NANOSECONDS.toMillis(stallNanos)}мс; " +
                    "снимков: ${captured.size}/$snapshotLimit; дамп: ${file.relativeTo(plugin.dataFolder).path}"
            )
            if (captured.any { it.deadlockedIds.isNotEmpty() }) {
                plugin.logger.severe("${KSLError.SYSTEM_WATCHDOG_DEADLOCK.tag} JVM обнаружила взаимную блокировку; подробности в watchdog-дампе.")
            }
        } catch (error: Throwable) {
            runCatching {
                plugin.logger.severe("${KSLError.SYSTEM_WATCHDOG_DUMP_FAILED.tag} Не удалось сохранить дамп: ${error.message}")
            }
        }
    }

    private fun buildReport(
        detectedAt: Instant,
        stallNanos: Long,
        snapshots: List<ThreadSnapshot>,
        allThreadCount: Int,
        recoveredDuringCapture: Boolean
    ): String = buildString {
        val runtime = Runtime.getRuntime()
        val memory = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val gc = ManagementFactory.getGarbageCollectorMXBeans()
        val deadlocks = snapshots.flatMap { it.deadlockedIds }.toSet()

        appendLine("KSL WATCHDOG DIAGNOSTIC REPORT")
        appendLine("Format: 2")
        appendLine("Detected at: $detectedAt")
        appendLine("Stalled for: ${TimeUnit.NANOSECONDS.toMillis(stallNanos)} ms")
        appendLine("Threshold: ${TimeUnit.NANOSECONDS.toMillis(thresholdNanos)} ms")
        appendLine("KSL version: ${plugin.description.version}")
        appendLine("Java: ${System.getProperty("java.version")}")
        appendLine("VM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("Processors: ${runtime.availableProcessors()}")
        appendLine("Heap: used=${memory.used} committed=${memory.committed} max=${memory.max} bytes")
        appendLine("Non-heap used: ${ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage.used} bytes")
        appendLine("GC: ${gc.joinToString { "${it.name}(count=${it.collectionCount},timeMs=${it.collectionTime})" }}")
        appendLine("KSL DB queues: write=${runCatching { plugin.dbQueueSize() }.getOrDefault(-1)}, read=${runCatching { plugin.dbQueryQueueSize() }.getOrDefault(-1)}")
        appendLine("Main-thread reference: ${mainThread?.name ?: "unavailable"}")
        appendLine()
        appendLine("=== KSL RUNTIME CONTEXT ===")
        val runtimeContext = runCatching { plugin.watchdogDiagnostics() }
            .getOrElse { listOf("Runtime context unavailable: ${it::class.simpleName}: ${it.message}") }
        runtimeContext.take(MAX_CONTEXT_LINES).forEach { appendLine(it.take(MAX_CONTEXT_LINE_LENGTH)) }
        if (runtimeContext.size > MAX_CONTEXT_LINES) {
            appendLine("KSL context truncated: ${runtimeContext.size - MAX_CONTEXT_LINES} additional lines omitted.")
        }
        appendLine("Snapshots captured: ${snapshots.size}/$snapshotLimit, interval=${snapshotDelay}ms")
        appendLine("JVM threads observed: $allThreadCount (capture limit $MAX_THREADS)")
        appendLine("Main thread recovered while collecting: $recoveredDuringCapture")
        appendLine("Deadlock detected: ${deadlocks.isNotEmpty()}")
        if (deadlocks.isNotEmpty()) appendLine("Deadlocked thread ids: ${deadlocks.sorted().joinToString()}")
        appendLine()
        appendLine("=== HOW TO READ THIS REPORT ===")
        appendLine("Compare the main-thread stack between snapshots. Frames that remain unchanged are likely blocking frames.")
        appendLine("BLOCKED means waiting to enter a monitor; WAITING/TIMED_WAITING means waiting or sleeping, not automatically a deadlock.")
        appendLine("KSL operation labels show context only; they do not prove KSL is the root cause.")
        appendLine("Deadlock detection is JVM-reported and stronger evidence than a single BLOCKED state.")
        appendLine("Thread and stack output is capped to keep the report bounded; see truncation notes.")
        appendLine()

        snapshots.forEachIndexed { index, snapshot ->
            appendLine("=== SNAPSHOT ${index + 1}/${snapshots.size} at ${snapshot.capturedAt} ===")
            appendLine("Threads observed: ${snapshot.totalThreadCount}; captured: ${snapshot.threads.size}; omitted: ${KSLWatchdogAnalysis.skippedThreads(snapshot.totalThreadCount, snapshot.threads.size)}")
            appendLine("Active KSL operation: ${snapshot.operation ?: "none"}")
            val main = mainThread
            val mainEntry = snapshot.threads.firstOrNull { it.first === main }
            if (main == null || mainEntry == null) {
                appendLine("Main thread stack unavailable in this snapshot.")
            } else {
                appendLine("--- MAIN THREAD ---")
                appendThread(mainEntry.first, mainEntry.second, snapshot.infos[mainEntry.first.id], snapshot.deadlockedIds, snapshot.cpuTimesNanos[mainEntry.first.id])
            }
            appendLine("--- ALL THREADS (max $MAX_THREADS, max $MAX_FRAMES frames each) ---")
            snapshot.threads.filterNot { it.first === main }.forEach { (thread, stack) ->
                appendThread(thread, stack, snapshot.infos[thread.id], snapshot.deadlockedIds, snapshot.cpuTimesNanos[thread.id])
            }
            val omitted = KSLWatchdogAnalysis.skippedThreads(snapshot.totalThreadCount, snapshot.threads.size)
            if (omitted > 0) appendLine("${KSLError.SYSTEM_WATCHDOG_THREAD_LIMIT.code}: omitted $omitted threads (capture cap $MAX_THREADS).")
            appendLine()
        }

        appendLine("=== DIAGNOSTIC SUMMARY ===")
        appendLine("Captured snapshots: ${snapshots.size}")
        appendLine("Unique deadlocked thread ids: ${deadlocks.size}")
        appendLine("Main thread name: ${mainThread?.name ?: "unknown"}")
        appendLine("Last active KSL operation: ${activeOperation ?: "none"}")
        appendLine("DB queue sizes: write=${runCatching { plugin.dbQueueSize() }.getOrDefault(-1)}, read=${runCatching { plugin.dbQueryQueueSize() }.getOrDefault(-1)}")
        appendLine("Captured snapshots: ${lastCapturedSnapshotCount}/$snapshotLimit")
        plugin.watchdogDiagnostics().forEach { appendLine(it) }
        val mainStacks = snapshots.mapNotNull { snapshot ->
            snapshot.threads.firstOrNull { it.first === mainThread }?.second?.map { it.toString() }
        }
        if (mainStacks.size >= 2) {
            val stableFrames = mainStacks.drop(1).fold(mainStacks.first()) { common, stack ->
                KSLWatchdogAnalysis.commonPrefix(common, stack)
            }
            appendLine("Stable leading main-thread frames across all snapshots: ${stableFrames.size}")
            stableFrames.take(20).forEach { appendLine("  at $it") }
            if (stableFrames.isNotEmpty()) appendLine("These frames persisted across samples; inspect the first KSL/non-server frame, but do not assume it is automatically the root cause.")
        }
        if (allThreadCount >= MAX_THREADS) appendLine("${KSLError.SYSTEM_WATCHDOG_THREAD_LIMIT.code}: thread listing may be truncated.")
    }

    private fun StringBuilder.appendThread(
        thread: Thread,
        stack: Array<StackTraceElement>,
        info: ThreadInfo?,
        deadlockedIds: Set<Long>,
        cpuTimeNanos: Long?
    ) {
        appendLine("\"${thread.name}\" id=${thread.id} state=${info?.threadState ?: thread.state} daemon=${thread.isDaemon}" +
            if (thread.id in deadlockedIds) " DEADLOCKED" else "")
        cpuTimeNanos?.let { appendLine("  CPU time: ${TimeUnit.NANOSECONDS.toMillis(it)} ms") }
        info?.let {
            appendLine("  lock: ${it.lockInfo ?: "none"}")
            if (it.lockOwnerId >= 0L) appendLine("  lock owner: ${it.lockOwnerName} (id=${it.lockOwnerId})")
            if (it.lockedMonitors.isNotEmpty()) appendLine("  locked monitors: ${it.lockedMonitors.joinToString()}")
            if (it.lockedSynchronizers.isNotEmpty()) appendLine("  locked synchronizers: ${it.lockedSynchronizers.joinToString()}")
        }
        stack.forEach { appendLine("\tat $it") }
    }

    private fun pruneOldDumps(directory: File) {
        val keep = maxDumps.coerceIn(1, 50)
        val dumps = directory.listFiles { file ->
            file.isFile && file.name.startsWith("watchdog-") && file.name.endsWith(".txt")
        } ?: return
        dumps.sortedByDescending { it.lastModified() }.drop(keep).forEach { file ->
            if (!file.delete()) {
                runCatching { plugin.logger.warning("${KSLError.SYSTEM_WATCHDOG_ROTATION_FAILED.tag} Не удалось удалить старый дамп: ${file.name}") }
            }
        }
    }

    fun latestDump(): File? = lastDumpFile?.takeIf { it.isFile }
    fun lastStallDurationMillis(): Long = lastStallMillis
    fun latestSnapshotCount(): Int = lastCapturedSnapshotCount

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { heartbeatTask?.cancel() }
        executor.shutdownNow()
        if (Thread.currentThread().name != "KSL-Watchdog-Diagnostics") {
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private companion object {
        const val MAX_THREADS = 300
        const val MAX_FRAMES = 80
        const val MAX_CONTEXT_LINES = 350
        const val MAX_CONTEXT_LINE_LENGTH = 600
    }
}
