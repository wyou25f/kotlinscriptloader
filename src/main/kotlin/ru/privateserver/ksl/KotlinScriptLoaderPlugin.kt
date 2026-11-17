package ru.privateserver.ksl

import com.zaxxer.hikari.HikariConfig
import de.tr7zw.changeme.nbtapi.NBT
import com.zaxxer.hikari.HikariDataSource
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.SimpleCommandMap
import org.bukkit.event.HandlerList
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import ru.privateserver.ksl.api.KSLLogger
import ru.privateserver.ksl.api.KSLLoggerConfig
import ru.privateserver.ksl.api.KSLLogLevel
import ru.privateserver.ksl.std.item.KSLCustomEnchantManager
import ru.privateserver.ksl.mcp.KSLMcpServer
import ru.privateserver.ksl.kslshu.KSLTaskScheduler
import ru.privateserver.ksl.kslshu.KSLTaskSchedulerEnvironment
import ru.privateserver.ksl.kslshu.KSLTaskSchedulerFactory
import ru.privateserver.ksl.kslshu.KSLTaskSchedulerRegistry
import ru.privateserver.ksl.kslshu.KSLTaskScope

internal fun isTaskOwnedBy(scriptName: String, taskId: Int, tasksByScript: Map<String, Set<Int>>): Boolean =
    tasksByScript[scriptName]?.contains(taskId) == true

class KotlinScriptLoaderPlugin : JavaPlugin() {

    @Volatile
    private var nbtApiReady = false

    internal fun requireNbtApiAvailable(operation: String) {
        check(nbtApiReady) {
            "[$operation] NBT-API is unavailable on this server. Check the startup warning and server-version compatibility."
        }
    }

    private fun initializeNbtApi() {
        val result = runCatching { NBT.preloadApi() }
        nbtApiReady = result.getOrDefault(false)
        if (nbtApiReady) {
            logger.info("[NBT] Item-NBT-API initialized successfully.")
        } else {
            logger.warning(
                "[NBT] Item-NBT-API could not initialize; NBT DSL calls will be disabled. " +
                    (result.exceptionOrNull()?.message ?: "The Minecraft server version or reflection mappings may be unsupported.")
            )
        }
    }

    internal lateinit var localeManager: KSLLocaleManager
        private set

    fun message(key: String, vararg values: Pair<String, Any?>): String =
        localeManager.message(key, *values)

    fun availableLanguages(): List<String> = localeManager.availableLanguages()

    fun selectLanguage(language: String): Boolean {
        if (!localeManager.containsLanguage(language)) return false
        val normalized = language.trim().lowercase(java.util.Locale.ROOT).replace('_', '-')
        config.set("language", normalized)
        saveConfig()
        localeManager.reload(normalized)
        return true
    }

    lateinit var database: HikariDataSource
        private set

    @Volatile private var dbThread: Thread? = null

    // A plain ThreadPoolExecutor rather than newSingleThreadExecutor(): the queue length is needed for diagnostics.
    // Writes remain serialized to preserve ordering. A bounded queue prevents an overloaded database
    // from turning sustained traffic into unbounded heap growth.
    val dbExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(10_000)
    ) { runnable -> Thread(runnable, "KSL-DB-Writer").apply { isDaemon = true }.also { dbThread = it } }

    // Read queries use a separate bounded pool so slow reads do not block ordered writes.
    val dbQueryExecutor = java.util.concurrent.ThreadPoolExecutor(
        4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(10_000)
    ) { runnable -> Thread(runnable, "KSL-DB-Reader").apply { isDaemon = true } }

    fun dbQueueSize(): Int = dbExecutor.queue.size
    fun dbQueryQueueSize(): Int = dbQueryExecutor.queue.size

    private val dbQueueWarnAt = 5_000 // Shared with the read-only health snapshot below.
    private val dbQueuePressureMonitor = KSLQueuePressureMonitor(warningAt = dbQueueWarnAt)

    @Volatile
    internal var watchdogMonitor: KSLWatchdogMonitor? = null
        private set

    private fun startWatchdogDiagnostics() {
        if (!config.getBoolean("diagnostics.watchdog.enabled", true)) return
        val thresholdSeconds = config.getLong("diagnostics.watchdog.threshold-seconds", 5L).coerceIn(2L, 300L)
        val pollMillis = config.getLong("diagnostics.watchdog.poll-interval-ms", 500L).coerceIn(100L, 5_000L)
        val maxDumps = config.getInt("diagnostics.watchdog.max-dumps", 10).coerceIn(1, 50)
        val snapshots = config.getInt("diagnostics.watchdog.snapshot-count", 3).coerceIn(1, 5)
        val snapshotInterval = config.getLong("diagnostics.watchdog.snapshot-interval-ms", 750L).coerceIn(250L, 2_000L)
        watchdogMonitor = KSLWatchdogMonitor(this, thresholdSeconds, pollMillis, maxDumps, snapshots, snapshotInterval).also { it.start() }
    }

    private fun startQueueMonitor() {
        // Queue-size reads are O(1). A 100-tick sample interval catches fast growth
        // without querying the database or adding work to either database executor.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            val pressure = dbQueuePressureMonitor.sample(dbQueueSize(), dbQueryQueueSize()) ?: return@Runnable
            val trend = "since-last-check: writes ${signed(pressure.writeGrowth)}, reads ${signed(pressure.readGrowth)}"
            val reason = if (pressure.reason == KSLQueuePressureMonitor.Reason.RAPID_GROWTH) {
                "early-warning"
            } else {
                "high-watermark"
            }
            logger.warning(
                "${KSLError.DB_QUEUE_LARGE.tag} ${message("console.database.queue-backlog", "writes" to pressure.writes, "reads" to pressure.reads)} " +
                    "($reason; $trend)"
            )
        }, 100L, 100L)
    }

    private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

    // Runs after every write queued before it. Inline on the DB thread: waiting on our own queue would deadlock.
    fun <T> dbCall(task: () -> T): T {
        if (Thread.currentThread() === dbThread) return task()
        try {
            return dbExecutor.submit(java.util.concurrent.Callable { task() }).get()
        } catch (ex: java.util.concurrent.ExecutionException) {
            throw ex.cause ?: ex
        }
    }

    lateinit var addonManager: KSLAddonManager
        private set

    lateinit var persistStore: KSLPersistStore
        private set

    lateinit var tableStore: KSLTableStore
        private set

    lateinit var guiManager: KSLGuiManager
        private set

    lateinit var effectManager: KSLEffectManager
        private set

    lateinit var entityManager: KSLEntityManager
        private set

    internal lateinit var customEnchantManager: KSLCustomEnchantManager
        private set

    lateinit var libraryRegistry: KSLLibraryRegistry
        private set

    val cooldownStore = KSLCooldownStore()

    @Volatile
    private var kslLoggingPolicy: KSLLoggingPolicy = KSLLoggingPolicy.DEFAULT

    var sandboxEnabled = false
        private set

    // strict = text filter + class-loader deny-list; text = only the text filter.
    var sandboxStrict = true
        private set

    var sandboxTrusted: Set<String> = emptySet()
        private set

    var sandboxPatterns: List<String> = KSLSandboxRules.forbidden
        private set

    var sandboxDenyList = KSLClassDenyList()
        private set

    var consoleBlocklist: Set<String> = emptySet()
        private set

    private var sandboxConsoleAllowlist: Set<String> = emptySet()
    private var sandboxDatabaseAllowlist: Set<String> = emptySet()

    private var maxListeners = 500
    private var maxTasks = 2000
    var taskFailureLimit: Int = 3
        private set
    private val limitWarned = ConcurrentHashMap.newKeySet<String>()

    fun isSandboxed(scriptId: String): Boolean = sandboxEnabled && scriptId !in sandboxTrusted

    internal fun requireScriptDatabaseAccess(scriptId: String) {
        KSLSandboxAccessPolicy.requireDatabaseAccess(
            scriptName = scriptId,
            sandboxed = isSandboxed(scriptId),
            allowedSandboxScripts = sandboxDatabaseAllowlist
        )
    }


    internal fun createScriptLogger(scriptId: String, name: String, config: KSLLoggerConfig): KSLLogger =
        createKslLogger("script:$scriptId", name, config)

    internal fun createAddonLogger(addonId: String, name: String, config: KSLLoggerConfig): KSLLogger =
        createKslLogger("addon:$addonId", name, config)

    private fun createKslLogger(ownerKey: String, name: String, config: KSLLoggerConfig): KSLLogger =
        KSLLogger.create(
            ownerKey = ownerKey,
            name = name,
            config = config,
            administratorMinimumLevel = { kslLoggingPolicy.minimumLevel(ownerKey) },
            maxMessageLength = { kslLoggingPolicy.maxMessageLength },
            sink = ::writeKslLog
        )

    private fun writeKslLog(record: ru.privateserver.ksl.api.KSLLogRecord) {
        val text = "[KSL] ${record.render(kslLoggingPolicy.maxMessageLength)}"
        when (record.level) {
            KSLLogLevel.TRACE -> logger.info("[TRACE] $text")
            KSLLogLevel.DEBUG -> logger.info("[DEBUG] $text")
            KSLLogLevel.INFO -> logger.info(text)
            KSLLogLevel.WARN -> if (record.cause == null) logger.warning(text)
                else logger.log(java.util.logging.Level.WARNING, text, record.cause)
            KSLLogLevel.ERROR -> if (record.cause == null) logger.severe(text)
                else logger.log(java.util.logging.Level.SEVERE, text, record.cause)
            KSLLogLevel.OFF -> Unit
        }
    }

    // Script problems are for the script's author to fix, not a fault of the server, so they are yellow unless asked otherwise.
    fun logScriptProblem(message: String) {
        if (config.getBoolean("logging.script-errors-as-warnings", true)) logger.warning(message) else logger.severe(message)
    }

    // A short in-game note for admins when a script failed during a reload; the details stay in the console and /ksl errors.
    fun notifyAdmins(message: String) {
        if (!config.getBoolean("notifications.admins-in-game", true)) return
        Bukkit.getOnlinePlayers().filter { it.hasPermission("ksl.admin") }.forEach { it.sendMessage(message) }
    }

    // "/minecraft:op Steve" and "OP" are the same command.
    fun isConsoleCommandBlocked(command: String): Boolean = KSLCommandPolicy.isBlocked(command, consoleBlocklist)

    fun canScriptExecuteConsole(scriptId: String, command: String): Boolean =
        KSLCommandPolicy.isAllowedForScript(command, isSandboxed(scriptId), consoleBlocklist, sandboxConsoleAllowlist)

    fun readSecurityConfig() {
        val loggingSection = config.getConfigurationSection("logging")
        val levelSection = loggingSection?.getConfigurationSection("owner-levels")
        val ownerLevels = levelSection?.getKeys(false)?.associateWith { levelSection.getString(it) ?: "" }.orEmpty()
        kslLoggingPolicy = KSLLoggingPolicy.parse(
            defaultLevel = loggingSection?.getString("default-level", "INFO"),
            ownerLevels = ownerLevels,
            maxMessageLength = loggingSection?.getInt("max-message-length", 4_096) ?: 4_096
        )

        sandboxEnabled = config.getBoolean("sandbox", false)
        sandboxStrict = !config.getString("security.sandbox-level", "strict").equals("text", ignoreCase = true)
        sandboxTrusted = config.getStringList("security.sandbox-trusted").map { it.trim().removeSuffix(".kts") }.filter { it.isNotEmpty() }.toSet()
        sandboxDatabaseAllowlist = config.getStringList("security.sandbox-database-allowlist")
            .map { it.trim().removeSuffix(".kts") }.filter { it.isNotEmpty() }.toSet()
        val allowedPatterns = config.getStringList("security.sandbox-allowed").map { it.trim() }.toSet()
        sandboxPatterns = (KSLSandboxRules.forbidden + config.getStringList("security.sandbox-extra-forbidden"))
            .filter { it !in allowedPatterns }.distinct()
        sandboxDenyList = KSLClassDenyList(config.getStringList("security.class-deny-extra"), config.getStringList("security.class-deny-allowed"))
        consoleBlocklist = (if (config.isList("security.console-command-blocklist")) config.getStringList("security.console-command-blocklist")
            else listOf("op", "deop", "stop", "restart", "reload", "rl")).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        sandboxConsoleAllowlist = config.getStringList("security.sandbox-console-allowlist")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        maxListeners = config.getInt("security.limits.max-listeners-per-script", 500).coerceAtLeast(10)
        maxTasks = config.getInt("security.limits.max-tasks-per-script", 2000).coerceAtLeast(10)
        taskFailureLimit = config.getInt("runtime.task-failure-limit", 3).coerceIn(1, 20)
    }

    var papiEnabled = false
        private set

    var luckPerms: KSLLuckPermsHook? = null
        private set

    var essentials: KSLEssentialsHook? = null
        private set

    var vault: KSLVaultHook? = null
        private set

    var skinRestorer: KSLSkinHook? = null
        private set

    var worldEdit: KSLWorldEditHook? = null
        private set

    var worldGuard: KSLWorldGuardHook? = null
        private set

    var tab: KSLTabHook? = null
        private set

    var authMe: KSLAuthMeHook? = null
        private set

    var skript: KSLSkriptHook? = null
        private set

    var discord: KSLDiscordHook? = null
        private set

    private lateinit var scriptEngine: KSLScriptEngine

    lateinit var workScheduler: ru.privateserver.ksl.rt.KSLWorkScheduler
        private set

    lateinit var schedulerRegistry: KSLTaskSchedulerRegistry
        private set

    lateinit var taskScheduler: KSLTaskScheduler
        private set

    internal fun taskScope(owner: String): KSLTaskScope = KSLTaskScope(taskScheduler, owner)

    /** Registers an addon-provided scheduler factory for later creation. */
    fun registerSchedulerFactory(id: String, factory: KSLTaskSchedulerFactory): AutoCloseable {
        check(::schedulerRegistry.isInitialized) { "KSL scheduler registry is not initialized yet" }
        return schedulerRegistry.register(id, factory)
    }

    /** Creates an independently managed scheduler registered under [id]. */
    fun createTaskScheduler(id: String): KSLTaskScheduler {
        check(::schedulerRegistry.isInitialized) { "KSL scheduler registry is not initialized yet" }
        return schedulerRegistry.create(id)
    }

    /** Returns registered scheduler engine identifiers for addon discovery. */
    fun availableTaskSchedulers(): Set<String> {
        check(::schedulerRegistry.isInitialized) { "KSL scheduler registry is not initialized yet" }
        return schedulerRegistry.available()
    }

    private fun initRuntimeWorkers() {
        val workers = config.getInt("runtime.cpu-threads", (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)).coerceIn(1, 64)
        val queueCapacity = config.getInt("runtime.queue-capacity", 256).coerceIn(1, 100_000)
        val perScriptLimit = config.getInt("runtime.max-tasks-per-script", 64).coerceIn(1, 10_000)
        if (::workScheduler.isInitialized) workScheduler.resize(workers)
        else workScheduler = ru.privateserver.ksl.rt.KSLWorkScheduler(workers, queueCapacity, perScriptLimit)
        if (!::schedulerRegistry.isInitialized) {
            schedulerRegistry = KSLTaskSchedulerRegistry.withOfficial(KSLTaskSchedulerEnvironment(workScheduler))
            taskScheduler = schedulerRegistry.create("ksl")
        }
    }

    fun submitWorker(scriptId: String, priority: ru.privateserver.ksl.rt.KSLWorkPriority, block: () -> Unit): Long =
        workScheduler.submit(scriptId, priority, block)

    fun cancelWorker(scriptId: String, taskId: Long): Boolean = workScheduler.cancel(scriptId, taskId)

    fun resizeWorkers(workers: Int): Int {
        require(workers in 1..64) { "Количество потоков должно быть от 1 до 64." }
        workScheduler.resize(workers)
        config.set("runtime.cpu-threads", workers)
        saveConfig()
        return workers
    }

    fun runtimeSnapshot(): ru.privateserver.ksl.rt.KSLRuntimeSnapshot =
        ru.privateserver.ksl.rt.KSLRuntimeMonitor.snapshot()

    internal fun compileCacheSnapshot(): KSLCompileCacheStats =
        if (::scriptEngine.isInitialized) scriptEngine.snapshot().compileCache
        else KSLCompileCacheStats(0, 0, 0, 0, 0)

    internal fun invalidateCompiledScriptCache(): Int =
        if (::scriptEngine.isInitialized) scriptEngine.clearCompileCache() else 0

    private val scriptDiagnostics = KSLScriptDiagnostics()
    private val scriptRuntimeScopes = ConcurrentHashMap<String, ru.privateserver.ksl.rt.KSLRuntimeScope>()
    private val generationManager = KSLGenerationManager<CompiledUnit>()

    /** Script-owned runtime resources belong to the generation currently being evaluated. */
    fun scriptRuntimeScope(scriptId: String): ru.privateserver.ksl.rt.KSLRuntimeScope {
        require(scriptId.isNotBlank()) { "scriptId must not be blank" }
        generationManager.currentScope(scriptId)?.let { return it }
        return scriptRuntimeScopes.computeIfAbsent(scriptId) {
            ru.privateserver.ksl.rt.KSLRT.scope("script:$it")
        }
    }

    internal fun generationSnapshots(): List<KSLGenerationSnapshot> = generationManager.snapshots()

    /** Closes a runtime generation that belongs to a diagnostic or short-lived context. */
    internal fun closeScriptRuntimeScope(scriptId: String) {
        scriptRuntimeScopes.remove(scriptId)?.close()
    }

    fun <T> measureScriptExecution(scriptId: String, operation: String, block: () -> T): T =
        scriptDiagnostics.measure(scriptId, operation, block)

    fun executionSnapshots(scriptId: String): List<KSLExecutionSnapshot> =
        scriptDiagnostics.snapshots(scriptId)

    /** Read-only, bounded context collected by the watchdog diagnostics thread. */
    fun watchdogDiagnostics(): List<String> = buildList {
        add("=== KSL RUNTIME CONTEXT ===")
        if (::scriptEngine.isInitialized) {
            val engine = scriptEngine.snapshot()
            add("Script Engine 2: compilations=${engine.compilations}, compileFailures=${engine.compilationFailures}, " +
                "evaluations=${engine.evaluations}, evaluationFailures=${engine.evaluationFailures}, " +
                "compileMs=${engine.compileNanos / 1_000_000.0}, evaluationMs=${engine.evaluationNanos / 1_000_000.0}")
            val cache = engine.compileCache
            add("Compile cache: hits=${cache.hits}, misses=${cache.misses}, hitRate=${"%.1f".format(java.util.Locale.ROOT, cache.hitRate * 100)}%, entries=${cache.size}/${cache.maxSize}, evictions=${cache.evictions}")
            engine.lastFailure?.let { add("Script Engine 2 last failure: $it") }
        }
        runCatching { runtimeSnapshot() }.onSuccess { snapshot ->
            add("JVM: heapUsed=${snapshot.heapUsedBytes} bytes, heapMax=${snapshot.heapMaxBytes} bytes, " +
                "nonHeapUsed=${snapshot.nonHeapUsedBytes} bytes, processors=${snapshot.processors}, " +
                "liveThreads=${snapshot.liveThreads}, daemonThreads=${snapshot.daemonThreads}")
            add("CPU: process=${snapshot.processCpuLoad?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "n/a"}, " +
                "system=${snapshot.systemCpuLoad?.let { "%.3f".format(java.util.Locale.ROOT, it) } ?: "n/a"}, " +
                "loadAverage=${snapshot.systemLoadAverage}, gcCount=${snapshot.gcCount}, gcTimeMs=${snapshot.gcTimeMillis}")
        }.onFailure { add("JVM snapshot unavailable: ${it::class.simpleName}: ${it.message}") }

        add("DB queues: write=${runCatching { dbQueueSize() }.getOrDefault(-1)}, " +
            "read=${runCatching { dbQueryQueueSize() }.getOrDefault(-1)}")
        val generations = generationManager.snapshots()
        val activeGenerations = generations.count { it.active }
        add("Script generations: active=$activeGenerations, initializing=${generations.size - activeGenerations}")
        generations.take(100).forEach { generation ->
            add("Generation ${generation.scriptId}#${generation.generation}: ${generation.state}, " +
                "resources=${generation.ownedResources}, stamp=${generation.sourceStamp.take(12)}")
            generation.failure?.let { add("Generation failure: $it") }
        }
        if (::workScheduler.isInitialized) {
            runCatching { workScheduler.snapshot() }.onSuccess { workers ->
                add("KSL workers: count=${workers.workers}, active=${workers.active}, queued=${workers.queued}, " +
                    "tracked=${workers.tracked}, completed=${workers.completed}, rejected=${workers.rejected}")
            }.onFailure { add("KSL worker snapshot unavailable: ${it::class.simpleName}: ${it.message}") }
        } else {
            add("KSL workers: not initialized")
        }

        val active = liveScripts.toList().sorted()
        add("Loaded scripts: ${active.size}")
        active.take(100).forEach { scriptId ->
            val operations = runCatching { executionSnapshots(scriptId) }.getOrDefault(emptyList())
                .sortedByDescending { it.totalNanos }
                .take(3)
            operations.forEach { metric ->
                add("Script '$scriptId' / ${metric.operation}: calls=${metric.calls}, failures=${metric.failures}, " +
                    "avgMs=${metric.averageNanos / 1_000_000.0}, maxMs=${metric.maxNanos / 1_000_000.0}, " +
                    "lastFailure=${metric.lastFailure ?: "none"}")
            }
        }
        if (active.size > 100) add("Additional loaded scripts omitted: ${active.size - 100}")
    }

    fun scriptDependencies(): List<KSLScriptDependencyInfo> =
        KSLScriptOrder.inspect(findScriptFiles(), ::scriptId)

    fun resourceSnapshot(scriptId: String): KSLResourceSnapshot {
        val activeTaskCount = synchronized(taskLock) {
            val activeTasks = tasksByScript[scriptId]
            activeTasks?.removeIf { taskId ->
                !Bukkit.getScheduler().isQueued(taskId) && !Bukkit.getScheduler().isCurrentlyRunning(taskId)
            }
            activeTasks?.size ?: 0
        }
        val listenerCount = synchronized(listenerLock) { listenersByScript[scriptId]?.size ?: 0 }
        val textPlaceholderCount = textPlaceholders.keys().count {
            textPlaceholders.ownerOf(it) == "script:$scriptId"
        }
        return KSLResourceSnapshot(
            scriptId = scriptId,
            loaded = scriptId in liveScripts,
            listeners = listenerCount,
            tasks = activeTaskCount,
            commands = trackedCommandCount(scriptId),
            placeholders = placeholdersByScript[scriptId]?.distinct()?.size ?: 0,
            textPlaceholders = textPlaceholderCount,
            hasGui = scriptId in scriptsWithGuis,
            hasEntities = ::entityManager.isInitialized && scriptId in entityManager.scriptNames()
        )
    }

    fun resourceSnapshots(): List<KSLResourceSnapshot> =
        (scriptIds() + knownScriptNames()).distinct().sorted().map(::resourceSnapshot)

    private val listenersByScript    = ConcurrentHashMap<String, MutableList<Listener>>()
    private val listenerLock = Any()
    private val commandsByScript     = ConcurrentHashMap<String, MutableList<DynamicCommand>>()
    private val commandLock = Any()
    private val placeholdersByScript = ConcurrentHashMap<String, MutableList<String>>()
    private val tasksByScript        = ConcurrentHashMap<String, MutableSet<Int>>()
    private val taskScopeCleanups = HashMap<Int, AutoCloseable>()
    private val taskLock = Any()
    private val scriptsWithGuis       = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Owner label is "script:<name>" for scripts and the addon id for addons.
    val placeholders                 = KSLOwnedRegistry<(OfflinePlayer) -> String?>()
    private var papiUnregister: (() -> Unit)? = null

    // @key@ tokens for message()/richMessage(); owner is "script:<name>", an addon id, or "config".
    val textPlaceholders = KSLOwnedRegistry<(Player?) -> String?>()

    fun registerTextPlaceholder(owner: String?, key: String, resolver: (Player?) -> String?) {
        val normalized = key.lowercase()
        val ownerLabel = owner ?: "external"
        if (normalized in KSLTextPlaceholders.reserved) {
            logger.warning("[$ownerLabel] ${KSLError.TEXT_PLACEHOLDER_RESERVED.tag} ${message("console.placeholder.reserved", "placeholder" to "@$normalized@")}")
            return
        }
        val previous = textPlaceholders.ownerOf(normalized)
        if (textPlaceholders.contains(normalized) && previous != owner) {
            logger.warning("[$ownerLabel] ${KSLError.PLACEHOLDER_CONFLICT.tag} ${message("console.placeholder.conflict", "placeholder" to "@$normalized@", "owner" to (previous ?: "?"))}")
        }
        textPlaceholders.put(normalized, resolver, owner)
    }

    fun resolveTextPlaceholder(key: String, player: Player?): String? {
        val resolver = textPlaceholders.get(key) ?: return null
        return try {
            resolver(player) ?: ""
        } catch (ex: Throwable) {
            KSLErrors.log(this, textPlaceholders.ownerOf(key)?.removePrefix("script:") ?: "?", "@$key@", ex, KSLError.TEXT_PLACEHOLDER_FAILED)
            ""
        }
    }

    // text-placeholders: in config.yml, re-read on every /ksl reload.
    private fun loadConfigTextPlaceholders() {
        textPlaceholders.releaseOwner("config")
        val section = config.getConfigurationSection("text-placeholders") ?: return
        section.getKeys(false).forEach { key ->
            val value = section.getString(key) ?: return@forEach
            registerTextPlaceholder("config", key) { _ -> value }
        }
    }

    val scriptsFolder: File by lazy { File(dataFolder, "scripts").apply { mkdirs() } }

    private val c9 = "§9"
    private val cb = "§b"
    private val cf = "§f"
    private val cg = "§a"
    private val ce = "§e"
    private val cc = "§c"
    private val c7 = "§7"
    private val bold = "§l"
    private val r = "§r"

    // Compiling is the heavy part of loading. At most scripts.compile-threads compiles run at once, which also keeps
    // the background doctor/validate from compiling next to a reload.
    private var compilePermits = java.util.concurrent.Semaphore(1)
    private var compileThreads = 1
    private var compilePool = KSLCompilePool(1)

    fun <T> withCompilePermit(block: () -> T): T {
        compilePermits.acquire()
        try {
            return block()
        } finally {
            compilePermits.release()
        }
    }

    private fun initCompileThreads() {
        val requestedThreads = config.getInt("scripts.compile-threads", 1).coerceIn(1, 8)
        if (requestedThreads == compileThreads) return
        compileThreads = requestedThreads
        compilePermits = java.util.concurrent.Semaphore(compileThreads)
        compilePool.close()
        compilePool = KSLCompilePool(compileThreads)
    }

    @Volatile
    private var mcpServer: KSLMcpServer? = null

    fun mcpStatusText(): String = mcpServer?.statusText() ?: "disabled"

    private fun startMcpServer() {
        if (!config.getBoolean("mcp.enabled", false)) return
        var candidate: KSLMcpServer? = null
        try {
            candidate = KSLMcpServer.fromConfig(this)
            candidate.start()
            mcpServer = candidate
        } catch (error: Exception) {
            runCatching { candidate?.close() }
            logger.warning("[MCP] Endpoint disabled: ${error.message ?: error::class.simpleName}")
        }
    }

    private val legacyColors = Regex("§[0-9A-Fa-fK-Ok-oRrXx]")

    // The console prints "§9" literally when it comes through the plugin logger, so the codes are stripped there.
    private fun stripColorsFromLog() {
        logger.filter = java.util.logging.Filter { record ->
            record.message = record.message?.replace(legacyColors, "")
            true
        }
    }

    override fun onEnable() {
        stripColorsFromLog()
        dataFolder.mkdirs()
        initializeNbtApi()
        saveDefaultConfig()
        localeManager = KSLLocaleManager(this)
        localeManager.reload(config.getString("language", "ru"))
        startWatchdogDiagnostics()
        loadConfigTextPlaceholders()
        KSLErrors.reset()

        addonManager   = KSLAddonManager(this)
        readSecurityConfig()
        initRuntimeWorkers()
        initCompileThreads()
        scriptEngine   = KSLScriptEngine(ScriptRunner(this))

        KSL.init(addonManager)
        KSLEffectShapes.registerDefaults(addonManager.effectShapes)
        KSLEntityAttributes.registerDefaults(addonManager.entityAttributes)

        getCommand("ksl")?.setExecutor(KSLCommandExecutor(this))
        startMcpServer()

        logger.info(message("lifecycle.starting", "version" to description.version))

        val dbOk = initDatabase()
        persistStore = KSLPersistStore(this)
        tableStore = KSLTableStore(this)
        guiManager = KSLGuiManager(this)
        guiManager.register()
        effectManager = KSLEffectManager(this)
        entityManager = KSLEntityManager(this)
        entityManager.register()
        customEnchantManager = KSLCustomEnchantManager(this).also { it.register() }
        libraryRegistry = KSLLibraryRegistry(this)
        setupIntegrations()
        discord = KSLDiscordHook(this).takeIf { it.channels().isNotEmpty() }
        generateAutocompleteStub()

        startWatcher()
        startQueueMonitor()
        loadScriptsAtStartup(dbOk)
    }

    private fun loadScriptsAtStartup(dbOk: Boolean) {
        if (config.getString("scripts.startup-load", "async").equals("sync", ignoreCase = true)) {
            val (loaded, failed) = loadAllScripts()
            printStartupSummary(dbOk, loaded, failed)
            return
        }
        logger.info(message("console.startup.async-scripts"))
        // The first tick comes after every plugin has enabled, so addon imports and services exist by then,
        // and the compile happens off the main thread instead of holding up the startup.
        Bukkit.getScheduler().runTask(this, Runnable {
            runAsyncReload(findScriptFiles(), emptyList()) { summary ->
                val loaded = summary?.loaded ?: 0
                val failed = summary?.failed ?: 0
                lastLoadStats = loaded to failed
                printStartupSummary(dbOk, loaded, failed)
            }
        })
    }

    private fun stopDatabaseExecutor(
        name: String,
        executor: java.util.concurrent.ExecutorService,
        onDropped: (Int) -> Unit
    ) {
        val result = KSLExecutorShutdown.shutdown(executor, 3L, java.util.concurrent.TimeUnit.SECONDS)
        if (result.droppedTasks > 0) onDropped(result.droppedTasks)
        if (!result.terminated || result.interrupted) {
            val reason = if (result.interrupted) {
                "shutdown wait was interrupted; forced cancellation was requested"
            } else {
                "executor did not terminate within 3 seconds; forced cancellation was requested"
            }
            logger.warning(
                "${KSLError.SYSTEM_SHUTDOWN.tag} " +
                    "${message("console.database.queue-stop-failed", "error" to "$name: $reason")}"
            )
        }
    }

    override fun onDisable() {
        shuttingDown.set(true)
        runCatching { mcpServer?.close() }
            .onFailure { logger.warning("[MCP] Failed to stop local endpoint cleanly: ${it::class.simpleName}") }
        mcpServer = null
        runCatching { watchdogMonitor?.stop() }
        watchdogMonitor = null
        runCatching { scriptWatcher?.stop() }
        runCatching { compilePool.close() }
        logger.info(message("lifecycle.stopping"))
        // A background compile must not race addon/classloader teardown. Shutdown is bounded
        // because a third-party compiler extension may ignore interruption.
        val reloadDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (reloadCompileInProgress.get() && System.nanoTime() < reloadDeadline) {
            try {
                Thread.sleep(10L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (reloadCompileInProgress.get()) logger.warning(message("console.shutdown.compile-in-progress"))
        runCatching { unloadAllScripts() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} ${message("console.shutdown.scripts-failed", "error" to it.message)}") }
        runCatching { generationManager.closeAll() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Не удалось закрыть generations: ${it.message}") }
        runCatching { if (::workScheduler.isInitialized) workScheduler.close() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} ${message("console.shutdown.workers-failed", "error" to it.message)}") }
        if (::scriptEngine.isInitialized) {
            if (!reloadCompileInProgress.get()) {
                runCatching { scriptEngine.close() }
                    .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Не удалось закрыть script engine: ${it.message}") }
            } else {
                logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Локальные classloader'ы оставлены открытыми: компиляция ещё выполняется.")
            }
        }
        runCatching { addonManager.shutdown() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} ${message("console.shutdown.addons-failed", "error" to it.message)}") }
        runCatching { papiUnregister?.invoke() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} ${message("console.shutdown.placeholderapi-failed", "error" to it.message)}") }
        runCatching {
            stopDatabaseExecutor("writer", dbExecutor) { dropped ->
                logger.warning("${KSLError.DB_WRITES_LOST.tag} ${message("console.database.writes-dropped", "count" to dropped)}")
            }
        }.onFailure {
            logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} ${message("console.database.queue-stop-failed", "error" to "writer: ${it.message}")}")
        }
        // Stop the reader pool independently: an interrupted writer wait must not skip this cleanup.
        runCatching {
            stopDatabaseExecutor("reader", dbQueryExecutor) { dropped ->
                logger.warning(message("console.database.reads-dropped", "count" to dropped))
            }
        }.onFailure {
            logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} ${message("console.database.queue-stop-failed", "error" to "reader: ${it.message}")}")
        }
        runCatching { if (::database.isInitialized) database.close() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} ${message("console.database.close-failed", "error" to it.message)}") }
        if (::customEnchantManager.isInitialized) runCatching { customEnchantManager.close() }
        KSL.shutdown()
        logger.info("$c9$bold■ ${message("console.shutdown.complete")}$r")
    }

    private fun printStartupSummary(dbOk: Boolean, loaded: Int, failed: Int) {
        fun flag(on: Boolean) = if (on) "${cg}✔ ${message("console.startup.found")}$r" else "${ce}○ ${message("console.startup.missing")}$r"
        val addonsCount = addonManager.registeredAddons().size
        val servicesCount = addonManager.getServiceKeys().size

        logger.info("$c9${bold}╔══════════════════════════════════════╗$r")
        logger.info("$c9${bold}║$r $cb$bold KotlinScriptLoader $cf v${description.version}$r")
        logger.info("$c9${bold}╠══════════════════════════════════════╣$r")
        logger.info("$c9${bold}║$r ${if (dbOk) cg else cc}${message("console.startup.database", "status" to message(if (dbOk) "console.startup.ok" else "console.startup.failed"))}$r")
        logger.info("$c9${bold}║$r ${if (failed == 0) cg else ce}${message("console.startup.scripts", "loaded" to loaded, "failed" to failed)}$r")
        logger.info("$c9${bold}║$r ${c7}PlaceholderAPI  : ${flag(papiEnabled)}")
        logger.info("$c9${bold}║$r ${c7}LuckPerms       : ${flag(luckPerms != null)}")
        logger.info("$c9${bold}║$r ${c7}EssentialsX     : ${flag(essentials != null)}")
        logger.info("$c9${bold}║$r ${c7}Vault           : ${flag(vault != null)}")
        logger.info("$c9${bold}║$r ${c7}SkinsRestorer   : ${flag(skinRestorer != null)}")
        logger.info("$c9${bold}║$r ${c7}WorldEdit       : ${flag(worldEdit != null)}")
        logger.info("$c9${bold}║$r ${c7}WorldGuard      : ${flag(worldGuard != null)}")
        logger.info("$c9${bold}║$r ${c7}TAB             : ${flag(tab != null)}")
        logger.info("$c9${bold}║$r ${c7}AuthMe          : ${flag(authMe != null)}")
        logger.info("$c9${bold}║$r ${c7}Skript          : ${flag(skript != null)}")
        logger.info("$c9${bold}║$r ${c7}${message(if (discord != null) "console.startup.discord" else "console.startup.discord-missing", "channels" to (discord?.channels()?.size ?: 0))}$r")
        logger.info("$c9${bold}║$r ${c7}${message(if (addonsCount > 0) "console.startup.addons" else "console.startup.addons-empty", "count" to addonsCount)}$r")
        logger.info("$c9${bold}║$r ${c7}${message(if (servicesCount > 0) "console.startup.services" else "console.startup.services-empty", "count" to servicesCount)}$r")
        logger.info("$c9${bold}║$r ${c7}${message(if (sandboxEnabled) "console.startup.sandbox" else "console.startup.sandbox-disabled", "level" to if (sandboxStrict) "strict" else "text")}$r")
        logger.info("$c9${bold}╚══════════════════════════════════════╝$r")
    }

    // SQLite by default; MySQL/MariaDB when database.type says so (manage it with phpMyAdmin or any SQL client).
    var dialect: KSLDialect = KSLDialect.SQLITE
        private set

    internal var databaseExecutionPolicy: KSLDatabaseExecutionPolicy = KSLDatabaseExecutionPolicy()
        private set

    // database.cache in config.yml; by default on for SQLite and off for MySQL/MariaDB.
    var tableCacheEnabled: Boolean = true
        private set

    private fun clearTableCaches() {
        if (this::tableStore.isInitialized) tableStore.clearCaches()
    }

    private fun readDatabaseSettings(): KSLDatabaseSettings = KSLDatabaseSettings.parse(
        type = config.getString("database.type", "sqlite") ?: "sqlite",
        host = config.getString("database.host", "localhost") ?: "localhost",
        port = config.getInt("database.port", 3306),
        name = config.getString("database.name", "ksl") ?: "ksl",
        user = config.getString("database.user", "") ?: "",
        // Keeping the password out of config.yml: leave it empty and set the KSL_DB_PASSWORD environment variable.
        password = (config.getString("database.password", "") ?: "").ifBlank { System.getenv("KSL_DB_PASSWORD") ?: "" },
        sslMode = config.getString("database.ssl-mode", "verify-full") ?: "verify-full",
        poolSize = config.getInt("database.pool-size", 4),
        allowInsecureTransport = config.getBoolean("database.allow-insecure-transport", false)
    )

    private fun initDatabase(): Boolean {
        var settings: KSLDatabaseSettings? = null
        return try {
            val parsed = readDatabaseSettings()
            settings = parsed
            databaseExecutionPolicy = KSLDatabaseExecutionPolicy.fromConfig(
                queryTimeoutSeconds = config.getInt("database.query-timeout-seconds", 10),
                maxQueryRows = config.getInt("database.query-max-rows", 10_000),
                maxBatchRows = config.getInt("database.max-batch-rows", 1_000)
            )
            if (parsed.poolSize > dbQueryExecutor.maximumPoolSize) {
                dbQueryExecutor.maximumPoolSize = parsed.poolSize
                dbQueryExecutor.corePoolSize = parsed.poolSize
            } else {
                dbQueryExecutor.corePoolSize = parsed.poolSize
                dbQueryExecutor.maximumPoolSize = parsed.poolSize
            }
            val cfg = HikariConfig().apply {
                poolName        = "ksl-pool"
                maximumPoolSize = parsed.poolSize
                connectionTimeout = 10_000L
                validationTimeout = 5_000L
                if (parsed.dialect.isRemote) {
                    maxLifetime = 1_500_000L
                    keepaliveTime = 30_000L
                }
                // Scripts get the raw pool; one unclosed connection starves everything else.
                leakDetectionThreshold = 10_000L
                if (parsed.dialect == KSLDialect.SQLITE) {
                    jdbcUrl         = "jdbc:sqlite:${File(dataFolder, "ksl.db").absolutePath}"
                    driverClassName = "org.sqlite.JDBC"
                    // Pool readers run next to the single DB writer thread; WAL + busy timeout avoid SQLITE_BUSY.
                    addDataSourceProperty("journal_mode", "WAL")
                    addDataSourceProperty("busy_timeout", "5000")
                } else {
                    jdbcUrl         = parsed.jdbcUrl()
                    driverClassName = "org.mariadb.jdbc.Driver"
                    username        = parsed.user
                    password        = parsed.password
                }
            }
            dialect = parsed.dialect
            tableCacheEnabled = config.getBoolean("database.cache", !parsed.dialect.isRemote)
            database = HikariDataSource(cfg)
            val applied = database.connection.use { Migrations.run(it, dialect) }
            logger.info(message("console.database.initialized", "database" to parsed.describe()))
            if (applied > 0) logger.info(message("console.database.migrations-applied", "count" to applied))
            true
        } catch (ex: Exception) {
            val rootMessage = generateSequence<Throwable>(ex) { it.cause }.last().message ?: ex.message
            logger.severe("${KSLError.DB_INIT_FAILED.tag} ${message("console.database.init-failed", "settings" to (settings?.describe() ?: message("console.database.settings-unknown")), "error" to rootMessage)}")
            if (settings?.dialect?.isRemote == true) {
                logger.severe(message("console.database.check-credentials", "database" to (settings?.name ?: "?")))
            }
            false
        }
    }

    // Present plugin + successful init => the hook is stored in its typed field and published in the hook registry.
    private fun <T : KSLHook> integrate(pluginName: String, label: String, create: () -> T): T? {
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) return null
        val hook = runCatching(create)
            .onFailure { logger.warning("${KSLError.HOOK_INIT_FAILED.tag} ${message("console.integration.hook-init-failed", "plugin" to label, "error" to it.message)}") }
            .getOrNull()
        hook?.let { addonManager.registerHook(it) }
        return hook
    }

    private fun setupIntegrations() {
        papiEnabled = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null
        if (papiEnabled)
            runCatching {
                val expansion = KSLPlaceholderExpansion(this)
                expansion.register()
                papiUnregister = { expansion.unregister() }
            }
                .onFailure { logger.warning("${KSLError.PAPI_REGISTER_FAILED.tag} ${message("console.integration.papi-register-failed", "error" to it.message)}") }

        luckPerms   = integrate("LuckPerms", "LuckPerms") { KSLLuckPermsHook() }
        essentials  = integrate("Essentials", "EssentialsX") { KSLEssentialsHook() }
        vault       = integrate("Vault", "Vault") { KSLVaultHook() }
        skinRestorer = integrate("SkinsRestorer", "SkinsRestorer") { KSLSkinHook() }
        worldEdit   = integrate("WorldEdit", "WorldEdit") { KSLWorldEditHook() }
        worldGuard  = integrate("WorldGuard", "WorldGuard") { KSLWorldGuardHook() }
        tab         = integrate("TAB", "TAB") { KSLTabHook() }
        authMe      = integrate("AuthMe", "AuthMe") { KSLAuthMeHook() }
        skript      = integrate("Skript", "Skript") { KSLSkriptHook() }
    }

    private fun generateAutocompleteStub() {
        val stub = File(scriptsFolder, ".autocomplete.kts")
        runCatching {
            val addonLines = addonManager.registeredAddons()
                .filter { it.addonDescription.isNotBlank() }
                .joinToString("\n") { "// Addon: ${it.addonId} v${it.addonVersion} — ${it.addonDescription}" }

            val generated = buildString {
                appendLine("// AUTO-GENERATED by KotlinScriptLoader v${description.version} — не редактировать")
                appendLine("// IntelliJ IDEA: File → Project Structure → Libraries → добавь этот файл")
                if (addonLines.isNotBlank()) { appendLine(); appendLine(addonLines) }
                appendLine()
                appendLine("import org.bukkit.entity.Player")
                appendLine("import org.bukkit.Sound")
                appendLine("import org.bukkit.SoundCategory")
                appendLine("import org.bukkit.event.Event")
                appendLine("import org.bukkit.event.EventPriority")
                appendLine("import org.bukkit.inventory.ItemStack")
                appendLine("import org.bukkit.OfflinePlayer")
                appendLine("import org.bukkit.configuration.file.YamlConfiguration")
                appendLine("import net.kyori.adventure.text.Component")
                appendLine("import java.sql.ResultSet")
                appendLine("import java.util.logging.Logger")
                appendLine()
                appendLine("object plugin {")
                appendLine("    val dataFolder: java.io.File get() = java.io.File(\".\")")
                appendLine("    val logger: Logger get() = Logger.getLogger(\"KSL\")")
                appendLine("    val name: String get() = \"KSL\"")
                appendLine("    val description: org.bukkit.plugin.PluginDescriptionFile get() = throw UnsupportedOperationException()")
                appendLine("    fun getConfig(): YamlConfiguration = YamlConfiguration()")
                appendLine("    fun saveDefaultConfig() = Unit")
                appendLine("    fun reloadConfig() = Unit")
                appendLine("}")
                appendLine("val logger: Logger get() = Logger.getLogger(\"KSL\")")
                appendLine("val scriptName: String get() = \"\"")
                appendLine()
                appendLine("val config: YamlConfiguration get() = YamlConfiguration()")
                appendLine("fun saveConfig() = Unit")
                appendLine("fun loadYaml(fileName: String): YamlConfiguration = YamlConfiguration()")
                appendLine("fun YamlConfiguration.message(path: String, vararg replacements: Pair<String, Any?>): String = \"\"")
                appendLine("fun YamlConfiguration.message(path: String, player: Player, vararg replacements: Pair<String, Any?>): String = \"\"")
                appendLine("fun YamlConfiguration.messageList(path: String, vararg replacements: Pair<String, Any?>): List<String> = emptyList()")
                appendLine("fun YamlConfiguration.messageList(path: String, player: Player, vararg replacements: Pair<String, Any?>): List<String> = emptyList()")
                appendLine("fun YamlConfiguration.richMessage(path: String, vararg replacements: Pair<String, Any?>): Component = Component.empty()")
                appendLine("fun YamlConfiguration.richMessage(path: String, player: Player, vararg replacements: Pair<String, Any?>): Component = Component.empty()")
                appendLine("fun <T> YamlConfiguration.getOrSetDefault(path: String, default: T): T = default")
                appendLine()
                appendLine("class KSLQuickTable { fun set(rowKey: String, columnKey: String, value: Any?) = Unit; fun get(rowKey: String, columnKey: String): String? = null; fun getInt(rowKey: String, columnKey: String, default: Int = 0): Int = default; fun getLong(rowKey: String, columnKey: String, default: Long = 0L): Long = default; fun getDouble(rowKey: String, columnKey: String, default: Double = 0.0): Double = default; fun getBoolean(rowKey: String, columnKey: String, default: Boolean = false): Boolean = default; fun increment(rowKey: String, columnKey: String, amount: Long = 1L): Long = 0L; fun delete(rowKey: String, columnKey: String? = null) = Unit; fun row(rowKey: String): Map<String, Any?> = emptyMap() }")
                appendLine("fun table(name: String): KSLQuickTable = KSLQuickTable()")
                appendLine()
                appendLine("fun dbExecute(sql: String, vararg args: Any?) = Unit")
                appendLine("fun dbQuery(sql: String, vararg args: Any?, block: (ResultSet) -> Unit) = Unit")
                appendLine()
                appendLine("fun runAsync(block: () -> Unit): Int = 0")
                appendLine("fun runSync(block: () -> Unit): Int = 0")
                appendLine("fun worker(priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> Unit): Long = 0L")
                appendLine("fun workerHandle(priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> Unit): ru.privateserver.ksl.rt.KSLWorkHandle = TODO()")
                appendLine("fun <T> workerFuture(priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> T): java.util.concurrent.CompletableFuture<T> = TODO()")
                appendLine("fun workerAfter(delayMillis: Long, priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> Unit): ru.privateserver.ksl.rt.KSLWorkHandle = TODO()")
                appendLine("fun workerAtFixedRate(periodMillis: Long, initialDelayMillis: Long = 0L, priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> Unit): ru.privateserver.ksl.rt.KSLWorkHandle = TODO()")
                appendLine("fun workerWithFixedDelay(delayMillis: Long, initialDelayMillis: Long = 0L, priority: ru.privateserver.ksl.rt.KSLWorkPriority = ru.privateserver.ksl.rt.KSLWorkPriority.NORMAL, block: () -> Unit): ru.privateserver.ksl.rt.KSLWorkHandle = TODO()")
                appendLine("fun cpu(block: () -> Unit): Long = 0L")
                appendLine("fun work(block: () -> Unit): Long = 0L")
                appendLine("fun cancelWorker(taskId: Long): Boolean = false")
                appendLine("fun cancelTask(taskId: Int) = Unit")
                appendLine("fun delay(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun every(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int = 0")
                appendLine("fun delayAsync(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun everyAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int = 0")
                appendLine("fun afterAsync(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun repeatAsync(ticks: Long, initialDelay: Long = 0L, block: () -> Unit): Int = 0")
                appendLine()
                appendLine("inline fun <reified T : Event> onEvent(priority: EventPriority = EventPriority.NORMAL, ignoreCancelled: Boolean = false, crossinline action: T.() -> Unit) = Unit")
                appendLine("fun registerCommand(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((Player, Array<String>) -> List<String>)? = null, action: (Player, Array<String>) -> Unit) = Unit")
                appendLine("fun registerCommandAny(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((org.bukkit.command.CommandSender, Array<String>) -> List<String>)? = null, action: (org.bukkit.command.CommandSender, Array<String>) -> Unit) = Unit")
                appendLine("fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable = AutoCloseable { }")
                appendLine("fun publish(topic: String, payload: Any? = null): Int = 0")
                appendLine("// ---- ru.privateserver.ksl.dsl (aliases, imported automatically) ----")
                appendLine("val Int.sec: Long get() = 0L")
                appendLine("val Long.sec: Long get() = 0L")
                appendLine("val Int.mins: Long get() = 0L")
                appendLine("val Long.mins: Long get() = 0L")
                appendLine("val Int.hours: Long get() = 0L")
                appendLine("val Long.hours: Long get() = 0L")
                appendLine("val Int.tick: Long get() = 0L")
                appendLine("val Long.tick: Long get() = 0L")
                appendLine("val Int.ticks: Long get() = 0L")
                appendLine("val Long.ticks: Long get() = 0L")
                appendLine("val Int.minute: Long get() = 0L")
                appendLine("val Long.minute: Long get() = 0L")
                appendLine("val Int.minutes: Long get() = 0L")
                appendLine("val Long.minutes: Long get() = 0L")
                appendLine("val Int.hour: Long get() = 0L")
                appendLine("val Long.hour: Long get() = 0L")
                appendLine("val Int.day: Long get() = 0L")
                appendLine("val Long.day: Long get() = 0L")
                appendLine("val Int.days: Long get() = 0L")
                appendLine("val Long.days: Long get() = 0L")
                appendLine("fun org.bukkit.command.CommandSender.msg(text: String) = Unit")
                appendLine("fun Player.actionBar(text: String) = Unit")
                appendLine("val online: Collection<Player> get() = emptyList()")
                appendLine("fun playerNamed(name: String): Player? = null")
                appendLine("fun chance(percent: Double): Boolean = false")
                appendLine("fun item(material: org.bukkit.Material, name: String? = null, lore: List<String> = emptyList(), amount: Int = 1): ItemStack = ItemStack(material)")
                appendLine("fun Player.giveItem(stack: ItemStack): Boolean = true")
                appendLine("fun log(message: String) = Unit")
                appendLine("fun logWarn(message: String) = Unit")
                appendLine("fun logError(message: String) = Unit")
                appendLine("fun later(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun laterAsync(ticks: Long, block: () -> Unit): Int = 0")
                appendLine("fun sync(block: () -> Unit): Int = 0")
                appendLine("fun async(block: () -> Unit): Int = 0")
                appendLine("inline fun <reified T : Event> on(priority: EventPriority = EventPriority.NORMAL, ignoreCancelled: Boolean = false, crossinline action: T.() -> Unit) = Unit")
                appendLine("fun onPlayerJoin(action: org.bukkit.event.player.PlayerJoinEvent.() -> Unit) = Unit")
                appendLine("fun onPlayerQuit(action: org.bukkit.event.player.PlayerQuitEvent.() -> Unit) = Unit")
                appendLine("fun onPlayerRespawn(action: org.bukkit.event.player.PlayerRespawnEvent.() -> Unit) = Unit")
                appendLine("fun onPlayerInteract(action: org.bukkit.event.player.PlayerInteractEvent.() -> Unit) = Unit")
                appendLine("fun onMobDeath(action: org.bukkit.event.entity.EntityDeathEvent.() -> Unit) = Unit")
                appendLine("fun onEntityDamage(action: org.bukkit.event.entity.EntityDamageEvent.() -> Unit) = Unit")
                appendLine("fun command(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((Player, Array<String>) -> List<String>)? = null, action: (Player, Array<String>) -> Unit) = Unit")
                appendLine("fun commandAny(name: String, aliases: List<String> = emptyList(), permission: String? = null, tabComplete: ((org.bukkit.command.CommandSender, Array<String>) -> List<String>)? = null, action: (org.bukkit.command.CommandSender, Array<String>) -> Unit) = Unit")
                appendLine("fun <T> cfg(path: String, default: T): T = default")
                appendLine("val String.mm: net.kyori.adventure.text.Component get() = throw UnsupportedOperationException()")
                appendLine("fun Player.title(title: String, subtitle: String = \"\", fadeInTicks: Int = 10, stayTicks: Int = 40, fadeOutTicks: Int = 10) = Unit")
                appendLine("fun Player.sound(key: String, volume: Float = 1f, pitch: Float = 1f) = Unit")
                appendLine("fun Player.sound(spec: KSLSoundSpec, at: org.bukkit.Location = location): Boolean = false")
                appendLine("fun org.bukkit.Location.sound(spec: KSLSoundSpec): Boolean = false")
                appendLine("class KSLSoundSpec { val key: String get() = \"\"; var volume: Float = 1f; var pitch: Float = 1f; var category: SoundCategory = SoundCategory.MASTER; fun volume(value: Number): KSLSoundSpec = this; fun pitch(value: Number): KSLSoundSpec = this; fun pitchRange(min: Number, max: Number): KSLSoundSpec = this; fun category(value: SoundCategory): KSLSoundSpec = this; fun playAt(location: org.bukkit.Location): Boolean = false; fun playTo(player: Player, at: org.bukkit.Location = player.location): Boolean = false; fun playNearby(location: org.bukkit.Location, radius: Double): Int = 0; fun repeatAt(location: org.bukkit.Location, periodTicks: Long = 20L, times: Int = -1): Int = 0; fun repeatTo(player: Player, periodTicks: Long = 20L, times: Int = -1, at: org.bukkit.Location = player.location): Int = 0; fun repeatNearby(location: org.bukkit.Location, radius: Double, periodTicks: Long = 20L, times: Int = -1): Int = 0 }")
                appendLine("class KSLSoundSequenceBuilder { fun at(tick: Int, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun at(tick: Int, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun at(tick: Long, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun at(tick: Long, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun after(ticks: Int, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun after(ticks: Int, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun after(ticks: Long, sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun after(ticks: Long, key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun then(sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this; fun then(key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSequenceBuilder = this }")
                appendLine("class KSLSoundSequence { fun playAt(location: org.bukkit.Location, repeats: Int = 1, gapTicks: Long = 0L): Int = 0; fun playTo(player: Player, repeats: Int = 1, gapTicks: Long = 0L, at: org.bukkit.Location = player.location): Int = 0; fun playNearby(location: org.bukkit.Location, radius: Double, repeats: Int = 1, gapTicks: Long = 0L): Int = 0 }")
                appendLine("fun sound(sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec = KSLSoundSpec()")
                appendLine("fun sound(key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec = KSLSoundSpec()")
                appendLine("fun music(sound: Sound, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec = KSLSoundSpec()")
                appendLine("fun music(key: String, block: KSLSoundSpec.() -> Unit = {}): KSLSoundSpec = KSLSoundSpec()")
                appendLine("fun soundSequence(block: KSLSoundSequenceBuilder.() -> Unit): KSLSoundSequence = KSLSoundSequence()")
                appendLine("fun Player.heal() = Unit")
                appendLine("fun loc(world: String, x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): org.bukkit.Location? = null")
                appendLine("fun org.bukkit.Location.offset(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0): org.bukkit.Location = this")
                appendLine("fun org.bukkit.Location.playersNear(radius: Double): Collection<Player> = emptyList()")
                appendLine("fun onJoin(action: org.bukkit.event.player.PlayerJoinEvent.() -> Unit) = Unit")
                appendLine("fun onQuit(action: org.bukkit.event.player.PlayerQuitEvent.() -> Unit) = Unit")
                appendLine("fun onPlayerDeath(action: org.bukkit.event.entity.PlayerDeathEvent.() -> Unit) = Unit")
                appendLine("fun onRespawn(action: org.bukkit.event.player.PlayerRespawnEvent.() -> Unit) = Unit")
                appendLine("fun onBlockBreak(action: org.bukkit.event.block.BlockBreakEvent.() -> Unit) = Unit")
                appendLine("fun onBlockPlace(action: org.bukkit.event.block.BlockPlaceEvent.() -> Unit) = Unit")
                appendLine("fun onInteract(action: org.bukkit.event.player.PlayerInteractEvent.() -> Unit) = Unit")
                appendLine("fun onChat(action: io.papermc.paper.event.player.AsyncChatEvent.() -> Unit) = Unit")
                appendLine("fun hasHook(hookId: String): Boolean = false")
                appendLine("inline fun <reified T : ru.privateserver.ksl.KSLHook> hook(hookId: String): T? = null")
                appendLine("inline fun <reified T : ru.privateserver.ksl.KSLHook> requireHook(hookId: String): T = throw UnsupportedOperationException()")
                appendLine()
                appendLine("fun parseMM(text: String): Component = Component.empty()")
                appendLine("fun broadcastMM(text: String) = Unit")
                appendLine("fun broadcast(message: String) = Unit")
                appendLine("fun Player.sendRichMessage(text: String) = Unit")
                appendLine("fun Player.sendActionBarMessage(text: String) = Unit")
                appendLine("fun Player.showRichTitle(title: String, subtitle: String = \"\", fadeIn: Int = 10, stay: Int = 70, fadeOut: Int = 20) = Unit")
                appendLine()
                appendLine("var Player.balance: Double?")
                appendLine("    get() = null")
                appendLine("    set(value) = Unit")
                appendLine("val Player.group: String? get() = null")
                appendLine("val Player.prefix: String? get() = null")
                appendLine()
                appendLine("fun registerPlaceholder(key: String, resolver: (OfflinePlayer) -> String?) = Unit")
                appendLine("fun placeholder(key: String, resolver: (OfflinePlayer) -> String?) = Unit")
                appendLine("fun textPlaceholder(key: String, resolver: (Player?) -> String?) = Unit")
                appendLine("fun registerTextPlaceholder(key: String, resolver: (Player?) -> String?) = Unit")
                appendLine()
                appendLine("fun luckPermsGroup(player: Player): String? = null")
                appendLine("fun luckPermsPrefix(player: Player): String? = null")
                appendLine("fun luckPermsSuffix(player: Player): String? = null")
                appendLine("fun hasGroup(player: Player, group: String): Boolean = false")
                appendLine()
                appendLine("fun balance(player: Player): Double? = null")
                appendLine("fun setBalance(player: Player, amount: Double) = Unit")
                appendLine("fun isAfk(player: Player): Boolean = false")
                appendLine()
                appendLine("fun vaultBalance(player: OfflinePlayer): Double? = null")
                appendLine("fun vaultDeposit(player: OfflinePlayer, amount: Double): Boolean = false")
                appendLine("fun vaultWithdraw(player: OfflinePlayer, amount: Double): Boolean = false")
                appendLine("fun vaultHas(player: OfflinePlayer, amount: Double): Boolean = false")
                appendLine("fun vaultFormat(amount: Double): String = \"\"")
                appendLine("fun vaultHasPermission(player: Player, node: String): Boolean = false")
                appendLine("fun vaultGroup(player: Player): String? = null")
                appendLine("fun vaultInGroup(player: Player, group: String): Boolean = false")
                appendLine()
                appendLine("fun setSkin(player: Player, skinName: String): Boolean = false")
                appendLine("fun setSkinRaw(player: Player, value: String, signature: String): Boolean = false")
                appendLine()
                appendLine("fun ItemStack.modifyNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) = Unit")
                appendLine("fun ItemStack.readNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) = Unit")
                appendLine("fun ItemStack.readComponents(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) = Unit")
                appendLine("fun ItemStack.modifyComponents(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) = Unit")
                appendLine("fun org.bukkit.entity.Entity.readNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) = Unit")
                appendLine("fun org.bukkit.entity.Entity.modifyNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) = Unit")
                appendLine("fun org.bukkit.entity.Entity.readPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) = Unit")
                appendLine("fun org.bukkit.entity.Entity.modifyPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) = Unit")
                appendLine("fun org.bukkit.block.TileState.readNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) = Unit")
                appendLine("fun org.bukkit.block.TileState.modifyNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) = Unit")
                appendLine("fun org.bukkit.block.TileState.readPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) = Unit")
                appendLine("fun org.bukkit.block.TileState.modifyPersistentNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) = Unit")
                appendLine("fun org.bukkit.block.Block.readChunkNBT(block: ru.privateserver.ksl.nbt.KSLNBTView.() -> Unit) = Unit")
                appendLine("fun org.bukkit.block.Block.modifyChunkNBT(block: ru.privateserver.ksl.nbt.KSLNBTCompound.() -> Unit) = Unit")
                appendLine("fun org.bukkit.block.Block.removeChunkNBT(key: String): Boolean = false")
                appendLine("fun ItemStack.setTag(key: String, value: String) = Unit")
                appendLine("fun ItemStack.getTag(key: String): String? = null")
                appendLine("fun ItemStack.hasTag(key: String): Boolean = false")

                appendLine("fun org.bukkit.persistence.PersistentDataHolder.setStringTag(key: String, value: String) = Unit")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.getStringTag(key: String): String? = null")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.hasStringTag(key: String): Boolean = false")
                appendLine("fun ItemStack.setStringTag(key: String, value: String) = Unit")
                appendLine("fun ItemStack.getStringTag(key: String): String? = null")
                appendLine("fun ItemStack.hasStringTag(key: String): Boolean = false")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.setIntTag(key: String, value: Int) = Unit")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.getIntTag(key: String): Int? = null")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.hasIntTag(key: String): Boolean = false")
                appendLine("fun ItemStack.setIntTag(key: String, value: Int) = Unit")
                appendLine("fun ItemStack.getIntTag(key: String): Int? = null")
                appendLine("fun ItemStack.hasIntTag(key: String): Boolean = false")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.setLongTag(key: String, value: Long) = Unit")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.getLongTag(key: String): Long? = null")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.hasLongTag(key: String): Boolean = false")
                appendLine("fun ItemStack.setLongTag(key: String, value: Long) = Unit")
                appendLine("fun ItemStack.getLongTag(key: String): Long? = null")
                appendLine("fun ItemStack.hasLongTag(key: String): Boolean = false")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.setDoubleTag(key: String, value: Double) = Unit")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.getDoubleTag(key: String): Double? = null")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.hasDoubleTag(key: String): Boolean = false")
                appendLine("fun ItemStack.setDoubleTag(key: String, value: Double) = Unit")
                appendLine("fun ItemStack.getDoubleTag(key: String): Double? = null")
                appendLine("fun ItemStack.hasDoubleTag(key: String): Boolean = false")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.setBooleanTag(key: String, value: Boolean) = Unit")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.getBooleanTag(key: String): Boolean? = null")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.hasBooleanTag(key: String): Boolean = false")
                appendLine("fun ItemStack.setBooleanTag(key: String, value: Boolean) = Unit")
                appendLine("fun ItemStack.getBooleanTag(key: String): Boolean? = null")
                appendLine("fun ItemStack.hasBooleanTag(key: String): Boolean = false")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.setUuidTag(key: String, value: java.util.UUID) = Unit")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.getUuidTag(key: String): java.util.UUID? = null")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.hasUuidTag(key: String): Boolean = false")
                appendLine("fun ItemStack.setUuidTag(key: String, value: java.util.UUID) = Unit")
                appendLine("fun ItemStack.getUuidTag(key: String): java.util.UUID? = null")
                appendLine("fun ItemStack.hasUuidTag(key: String): Boolean = false")
                appendLine("fun org.bukkit.persistence.PersistentDataHolder.removePersistentTag(key: String) = Unit")
                appendLine("fun ItemStack.removePersistentTag(key: String) = Unit")
                appendLine()
                appendLine("fun weSelection(player: Player): Pair<org.bukkit.Location, org.bukkit.Location>? = null")
                appendLine("fun weSelectionVolume(player: Player): Long? = null")
                appendLine("fun weFillSelection(player: Player, material: org.bukkit.Material): Int? = null")
                appendLine("fun weHasClipboard(player: Player): Boolean = false")
                appendLine()
                appendLine("fun wgRegionsAt(location: org.bukkit.Location): List<String> = emptyList()")
                appendLine("fun wgIsInRegion(location: org.bukkit.Location, regionId: String): Boolean = false")
                appendLine("fun wgCanBuild(player: Player, location: org.bukkit.Location): Boolean = true")
                appendLine("fun wgCanPvp(player: Player, location: org.bukkit.Location): Boolean = true")
                appendLine("fun wgFlag(location: org.bukkit.Location, flagName: String, player: Player? = null): String? = null")
                appendLine()
                appendLine("fun executeConsole(command: String) = Unit")
                appendLine()
                appendLine("class KSLGuiInstance { val pageCount: Int get() = 0; fun set(slot: Int, item: ItemStack, onClick: ((Player, org.bukkit.event.inventory.InventoryClickEvent) -> Unit)? = null) = Unit; fun button(slot: Int, item: ItemStack, onClick: (Player) -> Unit) = Unit; fun clear(slot: Int) = Unit; fun fill(item: ItemStack) = Unit; fun border(item: ItemStack) = Unit; fun allowItemMovement(slot: Int) = Unit; fun onClose(handler: (Player) -> Unit) = Unit; fun <T : Any> paginate(items: List<T>, slots: List<Int>, prevSlot: Int = -1, nextSlot: Int = -1, render: (T) -> ItemStack, onClick: (Player, T) -> Unit) = Unit; fun showPage(index: Int) = Unit; fun refresh() = Unit; fun open(player: Player) = Unit }")
                appendLine("fun gui(title: String, rows: Int = 3, builder: KSLGuiInstance.() -> Unit): KSLGuiInstance = KSLGuiInstance()")
                appendLine()
                appendLine("class KSLPersistProperty<T>(private val v: T) { operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = v; operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = Unit }")
                appendLine("fun <T> persist(key: String, persistent: Boolean = false, default: () -> T): KSLPersistProperty<T> = KSLPersistProperty(default())")
                appendLine()
                appendLine("val runtimeScope: ru.privateserver.ksl.rt.KSLRuntimeScope get() = ru.privateserver.ksl.rt.KSLRT.scope(\"autocomplete\")")
                appendLine("fun <T : AutoCloseable> own(resource: T): T = resource")
                appendLine("fun onUnload(action: () -> Unit): AutoCloseable = AutoCloseable {}")
                appendLine("inline fun <reified T : Any> service(key: String): T? = null")
                appendLine()
                appendLine("fun cooldown(player: Player, key: String, seconds: Long): Boolean = true")
                appendLine("fun cooldownRemaining(player: Player, key: String, seconds: Long): Long = 0L")
                appendLine("fun resetCooldown(player: Player, key: String) = Unit")
                appendLine()
                appendLine("fun export(key: String, value: Any) = Unit")
                appendLine("inline fun <reified T : Any> library(key: String): T? = null")
                appendLine("inline fun <reified T : Any> requireLibrary(key: String): T = throw UnsupportedOperationException()")
                appendLine()
                appendLine("fun discordSend(channel: String, message: String) = Unit")
                appendLine("fun discordSendAsync(channel: String, message: String, allowMentions: Boolean = false): java.util.concurrent.CompletableFuture<Boolean> = java.util.concurrent.CompletableFuture.completedFuture(false)")
                appendLine("fun discordEmbed(channel: String, title: String, description: String = \"\", color: Int = 0x5865F2, footer: String = \"\", fields: List<KSLDiscordEmbedField> = emptyList(), allowMentions: Boolean = false) = Unit")
                appendLine("fun discordEmbedAsync(channel: String, title: String, description: String = \"\", color: Int = 0x5865F2, footer: String = \"\", fields: List<KSLDiscordEmbedField> = emptyList(), allowMentions: Boolean = false): java.util.concurrent.CompletableFuture<Boolean> = java.util.concurrent.CompletableFuture.completedFuture(false)")
                appendLine("fun discordEmbedMessage(channel: String, title: String, description: String = \"\", color: Int = 0x5865F2, footer: String = \"\", fields: List<KSLDiscordEmbedField> = emptyList()) = Unit")
                appendLine("fun discordEmbedMessageAsync(channel: String, title: String, description: String = \"\", color: Int = 0x5865F2, footer: String = \"\", fields: List<KSLDiscordEmbedField> = emptyList(), allowMentions: Boolean = false): java.util.concurrent.CompletableFuture<Boolean> = java.util.concurrent.CompletableFuture.completedFuture(false)")
                appendLine("fun discord(channel: String, message: String) = Unit")
                appendLine("fun discordMessage(channel: String, message: String) = Unit")
                appendLine("fun discordAsync(channel: String, message: String, allowMentions: Boolean = false): java.util.concurrent.CompletableFuture<Boolean> = java.util.concurrent.CompletableFuture.completedFuture(false)")
                appendLine("fun discordMessageAsync(channel: String, message: String, allowMentions: Boolean = false): java.util.concurrent.CompletableFuture<Boolean> = java.util.concurrent.CompletableFuture.completedFuture(false)")
                appendLine()
                appendLine("class KSLEffectSpec { var count: Int = 1; var offsetX: Double = 0.0; var offsetY: Double = 0.0; var offsetZ: Double = 0.0; var extra: Double = 0.0; var durationTicks: Long = 100L; var intervalTicks: Long = 2L; var data: Any? = null; fun amount(value: Int) = Unit; fun particles(value: Int) = Unit; fun spread(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) = Unit; fun origin(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) = Unit; fun speed(value: Double) = Unit; fun every(ticks: Long) = Unit; fun forTicks(ticks: Long) = Unit; fun rotate(degrees: Double) = Unit; fun spin(degreesPerTick: Double) = Unit; fun circle(radius: Double, stepDegrees: Double = 15.0) = Unit; fun ring(radius: Double, points: Int = 24) = Unit; fun helix(radius: Double, turns: Double = 3.0, height: Double = 3.0) = Unit; fun spiral(radius: Double, turns: Double = 3.0, height: Double = 3.0) = Unit; fun lineTo(target: () -> org.bukkit.Location, points: Int = 20) = Unit; fun sphere(radius: Double, points: Int = 60, spinDegreesPerTick: Double = 0.0) = Unit; fun square(size: Double, pointsPerSide: Int = 10) = Unit; fun arc(radius: Double, startDegrees: Double = 0.0, sweepDegrees: Double = 180.0, points: Int = 30) = Unit; fun cylinder(radius: Double, height: Double, pointsPerRing: Int = 30) = Unit; fun cone(radius: Double, height: Double, points: Int = 60) = Unit; fun heart(size: Double = 1.0, points: Int = 80) = Unit; fun box(width: Double, height: Double, depth: Double, pointsPerEdge: Int = 8) = Unit; fun cube(size: Double = 2.0, pointsPerEdge: Int = 8) = Unit; fun point() = Unit; fun custom(points: (Long, org.bukkit.Location) -> List<org.bukkit.util.Vector>) = Unit; fun shape(name: String, vararg params: Pair<String, Any?>) = Unit; fun color(r: Int, g: Int, b: Int, size: Float = 1f) = Unit; fun color(hex: String, size: Float = 1f) = Unit; fun dust(hex: String, size: Float = 1f) = Unit; fun transition(fromHex: String, toHex: String, size: Float = 1f) = Unit; fun block(material: org.bukkit.Material) = Unit; fun item(stack: ItemStack) = Unit; fun particleData(value: Any?) = Unit; fun stopWhen(condition: () -> Boolean) = Unit; fun onEnd(block: () -> Unit) = Unit }")
                appendLine("fun org.bukkit.Location.playEffect(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.playEffect(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.Location.effect(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.effect(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun stopEffect(taskId: Int) = Unit")
                appendLine("fun org.bukkit.Location.particles(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.particles(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.Location.particle(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.particle(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun stopParticles(taskId: Int) = Unit")
                appendLine()
                appendLine("class KSLEntityBuilder { fun equip(template: String, dropChance: Float? = null) = Unit; fun name(text: String, visible: Boolean = true) = Unit; fun name(component: Component, visible: Boolean = true) = Unit; fun customName(text: String, visible: Boolean = true) = Unit; fun ai(on: Boolean = true) = Unit; fun baby(on: Boolean = true) = Unit; fun health(value: Double) = Unit; fun maxHealth(value: Double, healToFull: Boolean = true) = Unit; fun heal(amount: Double = 20.0) = Unit; fun target(target: org.bukkit.entity.LivingEntity?) = Unit; fun clearTarget() = Unit; fun persistent(on: Boolean = true) = Unit; fun removeWhenFarAway(on: Boolean = true) = Unit; fun gravity(on: Boolean = true) = Unit; fun collidable(on: Boolean = true) = Unit; fun canPickupItems(on: Boolean = true) = Unit; fun fire(seconds: Int) = Unit; fun velocity(x: Double, y: Double, z: Double) = Unit; fun teleport(location: org.bukkit.Location): Boolean = false; fun mainHand(item: ItemStack, dropChance: Float? = null) = Unit; fun offHand(item: ItemStack, dropChance: Float? = null) = Unit; fun helmet(item: ItemStack, dropChance: Float? = null) = Unit; fun chestplate(item: ItemStack, dropChance: Float? = null) = Unit; fun leggings(item: ItemStack, dropChance: Float? = null) = Unit; fun boots(item: ItemStack, dropChance: Float? = null) = Unit; fun potionTicks(type: org.bukkit.potion.PotionEffectType, ticks: Int, amplifier: Int = 0) = Unit; fun removePotion(type: org.bukkit.potion.PotionEffectType): Boolean = false; fun clearPotions() = Unit; fun moveTo(location: org.bukkit.Location, speed: Double = 1.0): Boolean = false; fun follow(target: org.bukkit.entity.LivingEntity, speed: Double = 1.0): Boolean = false; fun stopMoving() = Unit; fun lookAt(location: org.bukkit.Location) = Unit; fun lookAt(target: org.bukkit.entity.LivingEntity) = Unit; fun aware(on: Boolean = true) = Unit; fun aggressive(on: Boolean = true) = Unit; fun navigation(canFloat: Boolean? = null, canOpenDoors: Boolean? = null, canPassDoors: Boolean? = null) = Unit; fun attribute(name: String, value: Double) = Unit; fun speed(multiplier: Double) = Unit; fun damage(value: Double) = Unit; fun armor(value: Double) = Unit; fun potion(type: org.bukkit.potion.PotionEffectType, seconds: Int = -1, amplifier: Int = 0) = Unit; fun glow(on: Boolean = true) = Unit; fun silent(on: Boolean = true) = Unit; fun invulnerable(on: Boolean = true) = Unit; fun drops(vararg items: ItemStack, exp: Int? = null, keepDefault: Boolean = false) = Unit }")
                appendLine("class KSLEntityBehaviorBuilder { fun onTick(period: Long, initialDelay: Long = 0L, block: (org.bukkit.entity.LivingEntity) -> Unit) = Unit; fun onDeath(block: (org.bukkit.entity.LivingEntity, Player?) -> Unit) = Unit; fun onDamaged(block: (org.bukkit.entity.LivingEntity, org.bukkit.event.entity.EntityDamageEvent) -> Unit) = Unit; fun onAttack(block: (org.bukkit.entity.LivingEntity, org.bukkit.event.entity.EntityDamageByEntityEvent) -> Unit) = Unit }")
                appendLine("class KSLShopkeeperBuilder { var profession: org.bukkit.entity.Villager.Profession = org.bukkit.entity.Villager.Profession.LIBRARIAN; var villagerType: org.bukkit.entity.Villager.Type = org.bukkit.entity.Villager.Type.PLAINS; var level: Int = 5; var experience: Int = 1; var aiEnabled: Boolean = false; var aware: Boolean = false; var invulnerable: Boolean = true; var silent: Boolean = true; var collidable: Boolean = false; var canPickupItems: Boolean = false; var glowing: Boolean = false; var gravity: Boolean = true; fun trade(cost: ItemStack, result: ItemStack, secondCost: ItemStack? = null, maxUses: Int = Int.MAX_VALUE, rewardExperience: Boolean = false, villagerExperience: Int = 0, priceMultiplier: Float = 0f, ignoreDiscounts: Boolean = true) = Unit }")
                appendLine("fun spawnCustomEntities(location: org.bukkit.Location, template: String, amount: Int, perTick: Int = 1, intervalTicks: Long = 1L, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): Int = 0")
                appendLine("fun mob(location: org.bukkit.Location, template: String, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun mob(location: org.bukkit.Location, type: org.bukkit.entity.EntityType, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun spawnMob(location: org.bukkit.Location, type: org.bukkit.entity.EntityType, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun mobs(location: org.bukkit.Location, template: String, amount: Int, perTick: Int = 1, intervalTicks: Long = 1L, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): Int = 0")
                appendLine("fun mobs(location: org.bukkit.Location, type: org.bukkit.entity.EntityType, amount: Int, perTick: Int = 1, intervalTicks: Long = 1L, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): Int = 0")
                appendLine("fun spawnMobs(location: org.bukkit.Location, type: org.bukkit.entity.EntityType, amount: Int, perTick: Int = 1, intervalTicks: Long = 1L, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): Int = 0")
                appendLine("fun spawnShopkeeper(location: org.bukkit.Location, name: String, removeOnReload: Boolean = true, builder: KSLShopkeeperBuilder.() -> Unit): org.bukkit.entity.Villager = throw UnsupportedOperationException()")
                appendLine("fun shopkeeper(location: org.bukkit.Location, name: String, removeOnReload: Boolean = true, builder: KSLShopkeeperBuilder.() -> Unit): org.bukkit.entity.Villager = throw UnsupportedOperationException()")
                appendLine("fun openShopkeeper(player: Player, villager: org.bukkit.entity.Villager, force: Boolean = true): Boolean = false")
                appendLine("fun spawnCustomEntity(location: org.bukkit.Location, template: String, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun org.bukkit.entity.LivingEntity.behavior(builder: KSLEntityBehaviorBuilder.() -> Unit) = Unit")
            }
            // Rewriting an identical file on every reload makes the IDE re-index the stub for nothing.
            if (!stub.exists() || stub.readText() != generated) {
                stub.writeText(generated)
                logger.info(message("console.startup.autocomplete-created"))
            }
        }.onFailure { logger.warning("${KSLError.AUTOCOMPLETE_FAILED.tag} ${message("console.startup.autocomplete-failed", "error" to it.message)}") }
    }

    var lastLoadStats: Pair<Int, Int> = 0 to 0
        private set

    private var scriptWatcher: KSLScriptWatcher? = null

    private fun startWatcher() {
        if (!config.getBoolean("scripts.auto-reload", false)) return
        scriptWatcher = runCatching { KSLScriptWatcher(this).also { it.start() } }
            .onFailure { logger.warning("${KSLError.SCRIPT_WATCHER_FAILED.tag} ${message("console.startup.watcher-start-failed", "error" to it.message)}") }
            .getOrNull()
        if (scriptWatcher != null) logger.info(message("console.startup.watcher-enabled"))
    }

    // Folder-relative id without the extension, e.g. "economy/shop". It is the script's identity in logs,
    // persist keys, commands and @requires, so moving a script into another folder gives it a new id.
    fun scriptId(file: File): String = file.relativeTo(scriptsFolder).invariantSeparatorsPath.removeSuffix(".kts")

    // Every .kts under scripts/ (sub-folders included, up to 8 levels). Dot-files and folders starting with "." or "_" are skipped.
    private fun scanScriptFiles(): List<File> {
        val root = scriptsFolder
        val depth = if (config.getBoolean("scripts.recursive", true)) 8 else 1
        return root.walkTopDown().maxDepth(depth)
            .onEnter {
                it == root || (
                    !(it.name.startsWith(".") || it.name.startsWith("_")) &&
                        KSLScriptPathPolicy.isSafeChild(root, it)
                    )
            }
            .filter {
                it.isFile && it.extension == "kts" && !it.name.startsWith(".") &&
                    KSLScriptPathPolicy.isSafeChild(root, it)
            }
            .toList()
    }

    private val scriptIndex = KSLScriptIndex(::scanScriptFiles, ::scriptId)

    /** Force a filesystem refresh for explicit reload/validation operations without retaining File objects. */
    fun findScriptFiles(): List<File> = scriptIndex.refreshFiles()

    /** Reuse compact metadata for status and command suggestions. */
    fun scriptIds(): List<String> = scriptIndex.snapshot().ids

    fun scriptReloadTargets(): List<String> = scriptIndex.snapshot().reloadTargets

    internal fun invalidateScriptIndex() = scriptIndex.invalidate()

    // Last seen state of each script file; lets /ksl reload changed skip everything that did not change.
    private val scriptStamps = ConcurrentHashMap<String, String>()

    // Scripts whose code is currently registered (listeners, commands, ...).
    private val liveScripts = ConcurrentHashMap.newKeySet<String>()
    private val loadMillisById = ConcurrentHashMap<String, Long>()
    private val scriptLastErrorById = ConcurrentHashMap<String, String>()
    private val scriptLastAttemptAtById = ConcurrentHashMap<String, Long>()
    private val reloadBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reloadCompileInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun stampOf(file: File): String =
        if (!file.isFile) "<missing>" else runCatching { KSLSourceFingerprint.of(file.readBytes()) }.getOrElse { "<unreadable>" }

    fun scriptInfos(): List<KSLScriptInfo> {
        val fileIds = findScriptFiles().mapTo(linkedSetOf(), ::scriptId)
        // Include a live script whose source was deleted before the next reload.
        return (fileIds + liveScripts).sorted().map { id ->
            val info = KSLScriptInfo(id, id in liveScripts, loadMillisById[id] ?: -1L, KSLErrors.countFor(id))
            val error = scriptLastErrorById[id]
            info.sourceExists = id in fileIds
            info.lastError = error
            info.lastAttemptAtMillis = scriptLastAttemptAtById[id] ?: -1L
            info.state = when {
                info.loaded && error != null -> KSLScriptState.DEGRADED
                info.loaded -> KSLScriptState.LOADED
                error != null -> KSLScriptState.FAILED
                else -> KSLScriptState.UNLOADED
            }
            info
        }
    }

    fun compileScript(file: File): CompiledUnit = scriptEngine.compile(file)

    data class ReloadSummary(val loaded: Int, val failed: Int, val removed: Int)

    // Compile phase: no Bukkit API, so it runs on any thread. Several files compile side by side with scripts.compile-threads > 1.
    private fun compileBatch(files: List<File>): Map<String, CompiledUnit> {
        if (compileThreads <= 1 || files.size <= 1) return files.associate { scriptId(it) to scriptEngine.compile(it) }
        val units = compilePool.map(files, waitWhenSaturated = !Bukkit.isPrimaryThread()) { file ->
            scriptEngine.compile(file)
        }
        return files.indices.associate { scriptId(files[it]) to units[it] }
    }

    // Swap phase (main thread): the running version is replaced only after the new one compiled,
    // so a typo in a script no longer takes the working version down with it.
    private fun applyUnit(unit: CompiledUnit): Boolean {
        val id = unit.id
        if (!unit.ok) {
            scriptLastAttemptAtById[id] = System.currentTimeMillis()
            scriptLastErrorById[id] = unit.errors.firstOrNull() ?: unit.crash?.message ?: "Compilation failed"
            scriptEngine.reportFailure(unit)
            if (id in liveScripts) {
                logger.warning("[$id] ${KSLError.SCRIPT_KEPT_PREVIOUS.tag} ${message("console.script.previous-version-kept")}")
            }
            // A compile failure never creates a generation and never replaces the active version.
            scriptStamps[id] = unit.sourceStamp.ifBlank { stampOf(unit.file) }
            addonManager.publish("ksl.script.failed", KSLScriptEvent(id, unit.compileMillis, unit.errors.firstOrNull() ?: unit.crash?.message))
            val code = unit.failure ?: KSLError.SCRIPT_COMPILE
            notifyAdmins("§c✘ §f$id§7: ${unit.errors.firstOrNull() ?: unit.crash?.message ?: "ошибка"} §8[${code.code}] §7(подробнее: /ksl errors)")
            return false
        }

        val startedAt = System.nanoTime()
        val previous = generationManager.activeGeneration(id)
        val candidate = generationManager.begin(id, unit.sourceStamp, unit)

        // Stage replacement registrations while keeping the previous generation scope alive.
        // The owner-indexed Bukkit registries are still detached by script id, but the old
        // generation scope is not disposed until the candidate is committed or recovery fails.
        unloadScript(id, closeGeneration = false, publishUnloaded = false)
        val ok = try {
            generationManager.withCandidate(candidate) { scriptEngine.evaluate(unit) }
        } catch (error: Throwable) {
            logger.log(java.util.logging.Level.SEVERE, "[$id] Ошибка инициализации поколения ${candidate.number}", error)
            false
        }

        var restored = false
        if (ok) {
            generationManager.activate(candidate)
            liveScripts.add(id)
            scriptLastErrorById.remove(id)
        } else {
            val failure = scriptEngine.snapshot().lastFailure ?: "generation initialization failed"
            // Remove partial registrations and candidate-owned resources, without disposing
            // the previous active scope that may still be needed by old context closures.
            unloadScript(id, closeGeneration = false, publishUnloaded = false)
            generationManager.fail(candidate, IllegalStateException(failure))

            if (previous != null) {
                val recovery = generationManager.begin(id, previous.sourceStamp, previous.artifact)
                restored = try {
                    generationManager.withCandidate(recovery) {
                        scriptEngine.evaluate(previous.artifact)
                    }
                } catch (error: Throwable) {
                    logger.log(java.util.logging.Level.SEVERE, "[$id] Не удалось восстановить поколение ${previous.number}", error)
                    false
                }

                if (restored) {
                    generationManager.activate(recovery)
                    liveScripts.add(id)
                    logger.warning(
                        "[$id] Новое поколение не запустилось; восстановлено предыдущее содержимое " +
                            "в поколении ${recovery.number}."
                    )
                } else {
                    unloadScript(id, closeGeneration = false, publishUnloaded = false)
                    generationManager.fail(recovery, IllegalStateException("previous generation recovery failed"))
                    generationManager.retireActive(id)
                    addonManager.publish("ksl.script.unloaded", KSLScriptEvent(id))
                }
            }
            scriptLastErrorById[id] = if (restored) {
                "Новая версия не запустилась; восстановлена предыдущая версия (generation ${previous?.number}). Причина: $failure"
            } else {
                "Ошибка выполнения нового поколения: $failure" +
                    if (previous != null) "; восстановить предыдущую версию не удалось" else ""
            }
        }

        scriptLastAttemptAtById[id] = System.currentTimeMillis()
        val totalMs = unit.compileMillis + (System.nanoTime() - startedAt) / 1_000_000
        loadMillisById[id] = totalMs
        scriptStamps[id] = unit.sourceStamp.ifBlank { stampOf(unit.file) }

        if (restored) {
            // Consumers should know that the prior script version is live again even
            // though the attempted replacement failed.
            addonManager.publish(
                "ksl.script.loaded",
                KSLScriptEvent(id, totalMs, "previous generation restored after failed reload")
            )
        }
        val eventError = if (ok) null else scriptLastErrorById[id]
        addonManager.publish(
            if (ok) "ksl.script.loaded" else "ksl.script.failed",
            KSLScriptEvent(id, totalMs, eventError)
        )
        if (!ok) {
            val suffix = if (restored) "Предыдущая версия восстановлена." else "Рабочей версии нет."
            notifyAdmins("§c✘ §f$id§7: ошибка выполнения. $suffix Подробности: /ksl errors")
        }
        return ok
    }

    private fun swap(ordered: List<File>, units: Map<String, CompiledUnit>, removed: Collection<String>): ReloadSummary {
        removed.forEach {
            unloadScript(it)
            scriptLastErrorById.remove(it)
            scriptLastAttemptAtById.remove(it)
        }
        var loaded = 0
        var failed = 0
        ordered.forEach { if (applyUnit(units.getValue(scriptId(it)))) loaded++ else failed++ }
        return ReloadSummary(loaded, failed, removed.size)
    }

    // Blocking flavour, for startup and for callers that already sit on the main thread.
    private fun reloadNow(files: List<File>, removed: Collection<String>): ReloadSummary {
        check(!shuttingDown.get()) { "KotlinScriptLoader is shutting down; script reload is unavailable" }
        check(reloadBusy.compareAndSet(false, true)) { "A script reload is already in progress" }
        return try {
            val ordered = KSLScriptOrder.sort(files, { scriptId(it) }, logger)
            swap(ordered, compileBatch(ordered), removed)
        } finally {
            reloadBusy.set(false)
        }
    }

    // Compiles on a worker thread while the server keeps ticking, then swaps on the main thread.
    // done receives null when another reload is running or shutdown has started.
    private fun runAsyncReload(files: List<File>, removed: Collection<String>, done: (ReloadSummary?) -> Unit) {
        if (shuttingDown.get() || !reloadBusy.compareAndSet(false, true)) {
            done(null)
            return
        }
        reloadCompileInProgress.set(true)
        try {
            Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
                var ordered = emptyList<File>()
                val units = try {
                    ordered = KSLScriptOrder.sort(files, { scriptId(it) }, logger)
                    val compiled = compileBatch(ordered)
                    // Initialise the scripting runtime before releasing the shutdown barrier.
                    runCatching { scriptEngine.warmUp() }
                    compiled
                } catch (ex: Throwable) {
                    logger.severe("${KSLError.SCRIPT_RUNTIME.tag} ${message("console.script.background-compile-failed", "type" to ex::class.simpleName, "error" to ex.message)}")
                    null
                } finally {
                    reloadCompileInProgress.set(false)
                }

                // A compile already in progress may finish while the plugin is disabling.
                if (shuttingDown.get() || !isEnabled) {
                    reloadBusy.set(false)
                    return@Runnable
                }
                try {
                    Bukkit.getScheduler().runTask(this, Runnable {
                        try {
                            if (!shuttingDown.get() && isEnabled) {
                                done(if (units == null) ReloadSummary(0, ordered.size, 0) else swap(ordered, units, removed))
                            }
                        } finally {
                            reloadBusy.set(false)
                        }
                    })
                } catch (ex: Throwable) {
                    reloadBusy.set(false)
                    logger.warning(message("console.script.reload-handoff-failed", "error" to ex.message))
                }
            })
        } catch (ex: Throwable) {
            reloadCompileInProgress.set(false)
            reloadBusy.set(false)
            throw ex
        }
    }

    fun loadAllScripts(): Pair<Int, Int> {
        val summary = reloadNow(findScriptFiles(), emptyList())
        lastLoadStats = summary.loaded to summary.failed
        return lastLoadStats
    }

    // Public so command handlers can run the compiler check off the main thread.
    fun selfTestCompiler(): Pair<Boolean, String> = scriptEngine.selfTestCompiler()

    fun sandboxProbe(): Pair<Boolean, String> = scriptEngine.sandboxProbe()

    fun validateScripts(target: String? = null): List<KSLScriptValidationResult> {
        val files = if (target.isNullOrBlank()) findScriptFiles()
            else listOfNotNull(resolveScriptFile(target))
        val validator = ScriptValidator(this)
        // Validation is deliberately sequential on the background command worker. It shares
        // the compiler semaphore with reloads and avoids a burst of compiler/classloader work.
        return files.map { validator.validateDetailed(it) }
    }

    fun validateAllScripts(): Map<String, List<String>> =
        validateScripts().associate { it.scriptId to it.errors }

    fun writeValidationReport(results: List<KSLScriptValidationResult>): File =
        KSLValidationReportWriter.write(File(dataFolder, "reports"), results)

    fun verifyTrackedCommands(): List<String> {
        val broken = mutableListOf<String>()
        val commands = synchronized(commandLock) {
            commandsByScript.values.flatMap { it.toList() }
        }
        commands.forEach { cmd ->
            val bare = Bukkit.getCommandMap().getCommand(cmd.name)
            val prefixed = Bukkit.getCommandMap().getCommand("${name.lowercase()}:${cmd.name}")
            if (bare !== cmd && prefixed !== cmd) broken.add(cmd.name)
        }
        return broken
    }

    fun checkDatabaseTables(): Boolean = try {
        database.connection.use { conn ->
            conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ksl_scripts").close() }
            conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ksl_persist").close() }
            conn.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM ksl_table").close() }
        }
        true
    } catch (ex: Exception) {
        logger.warning("${KSLError.DB_DOCTOR_TABLES.tag} ${message("console.database.doctor-tables-failed", "error" to ex.message)}")
        false
    }

    fun unloadAllScripts() {
        knownScriptNames().forEach { unloadScript(it) }
    }

    /**
     * Fatal script signals may originate on async workers. Bukkit registrations are
     * therefore always cleaned up on the server thread.
     */
    fun stopScriptAfterFatal(scriptName: String) {
        val stop = Runnable { unloadScript(scriptName) }
        if (Bukkit.isPrimaryThread()) {
            stop.run()
        } else if (isEnabled) {
            runCatching { Bukkit.getScheduler().runTask(this, stop) }
                .onFailure { logger.severe("[$scriptName] Не удалось запланировать остановку после fatal(): ${it.message}") }
        }
    }

    private fun unloadScript(
        scriptName: String,
        closeGeneration: Boolean = true,
        publishUnloaded: Boolean = true
    ) {
        if (::customEnchantManager.isInitialized) runCatching { customEnchantManager.unregisterOwner(scriptName) }
        if (::workScheduler.isInitialized) runCatching { workScheduler.cancelOwner(scriptName) }
        if (::effectManager.isInitialized) runCatching { effectManager.cleanupForScript(scriptName) }
        scriptStamps.remove(scriptName)
        limitWarned.remove("$scriptName|L")
        limitWarned.remove("$scriptName|T")
        loadMillisById.remove(scriptName)
        scriptDiagnostics.clearScript(scriptName)
        if (closeGeneration) {
            runCatching { generationManager.retireActive(scriptName) }
                .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось закрыть generation scope: ${it.message}") }
        }
        runCatching { closeScriptRuntimeScope(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось закрыть runtime-ресурсы: ${it.message}") }
        if (::persistStore.isInitialized) runCatching { persistStore.clearScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось очистить кеш persist: ${it.message}") }
        if (liveScripts.remove(scriptName) && publishUnloaded) {
            addonManager.publish("ksl.script.unloaded", KSLScriptEvent(scriptName))
        }
        runCatching {
            val (taskIds, cleanupHandles) = synchronized(taskLock) {
                val ids = tasksByScript.remove(scriptName)?.toList().orEmpty()
                ids to ids.mapNotNull { taskScopeCleanups.remove(it) }
            }
            cleanupHandles.forEach { it.close() }
            taskIds.forEach { Bukkit.getScheduler().cancelTask(it) }
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось отменить часть тасков: ${it.message}") }

        runCatching {
            val listeners = synchronized(listenerLock) {
                listenersByScript.remove(scriptName)?.toList().orEmpty()
            }
            listeners.forEach { HandlerList.unregisterAll(it) }
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось снять часть listener'ов: ${it.message}") }

        runCatching {
            val commands = synchronized(commandLock) {
                commandsByScript.remove(scriptName)?.toList().orEmpty()
            }
            if (commands.isNotEmpty()) unregisterCommands(commands)
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось снять часть команд: ${it.message}") }

        runCatching {
            placeholdersByScript.remove(scriptName)
            // Only this script's own entries: another script may have taken over a key with the same name.
            placeholders.releaseOwner("script:$scriptName")
            textPlaceholders.releaseOwner("script:$scriptName")
        }.onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось снять часть плейсхолдеров: ${it.message}") }

        runCatching { addonManager.notifyContextDestroyed(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Ошибка при уведомлении аддонов о выгрузке: ${it.message}") }

        runCatching { guiManager.closeGuisForScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось закрыть открытые GUI: ${it.message}") }
        scriptsWithGuis.remove(scriptName)

        runCatching { libraryRegistry.clearScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось очистить библиотечные экспорты: ${it.message}") }

        runCatching { entityManager.cleanupForScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось убрать кастомных сущностей скрипта: ${it.message}") }

        runCatching { cooldownStore.clearScript(scriptName) }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось очистить кулдауны скрипта: ${it.message}") }
    }

    private fun unregisterCommands(commands: Collection<DynamicCommand>) {
        if (commands.isEmpty()) return
        val commandSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Command, Boolean>())
        commandSet.addAll(commands)
        scheduleCommandSync()
        val viaApi = runCatching { removeOurCommands(Bukkit.getCommandMap().knownCommands, commandSet) }
        if (viaApi.isSuccess) return
        val viaReflection = runCatching { removeOurCommands(reflectKnownCommands(), commandSet) }
        if (viaReflection.isFailure)
            logger.warning("${KSLError.COMMAND_MAP_UNAVAILABLE.tag} commandMap недоступен для изменения — старые команды сохранятся до рестарта.")
    }

    private fun removeOurCommands(knownCommands: MutableMap<String, Command>, ourCommands: Set<Command>) {
        val it = knownCommands.entries.iterator()
        while (it.hasNext()) { if (it.next().value in ourCommands) it.remove() }
    }

    private fun reflectKnownCommands(): MutableMap<String, Command> {
        val field = SimpleCommandMap::class.java.getDeclaredField("knownCommands")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(Bukkit.getCommandMap() as SimpleCommandMap) as MutableMap<String, Command>
    }

    // Re-reads config.yml and everything derived from it.
    private fun refreshConfig() {
        reloadConfig()
        databaseExecutionPolicy = KSLDatabaseExecutionPolicy.fromConfig(
            queryTimeoutSeconds = config.getInt("database.query-timeout-seconds", 10),
            maxQueryRows = config.getInt("database.query-max-rows", 10_000),
            maxBatchRows = config.getInt("database.max-batch-rows", 1_000)
        )
        localeManager.reload(config.getString("language", "ru"))
        loadConfigTextPlaceholders()
        clearTableCaches()
        readSecurityConfig()
        initRuntimeWorkers()
        initCompileThreads()
        discord = KSLDiscordHook(this).takeIf { it.channels().isNotEmpty() }
        generateAutocompleteStub()
    }

    fun reloadScripts(): Pair<Int, Int> {
        refreshConfig()
        val files = findScriptFiles()
        val present = files.map { scriptId(it) }.toSet()
        val summary = reloadNow(files, knownScriptNames() - present)
        lastLoadStats = summary.loaded to summary.failed
        return lastLoadStats
    }

    // done gets null when a reload is already in progress.
    fun reloadScriptsAsync(done: (Pair<Int, Int>?) -> Unit) {
        if (reloadBusy.get()) {
            done(null)
            return
        }
        refreshConfig()
        val files = findScriptFiles()
        val present = files.map { scriptId(it) }.toSet()
        runAsyncReload(files, knownScriptNames() - present) { summary ->
            if (summary == null) {
                done(null)
            } else {
                lastLoadStats = summary.loaded to summary.failed
                done(lastLoadStats)
            }
        }
    }

    // The file behind a script name, or null (with the reason logged) when the name is unusable.
    private fun resolveScriptFile(scriptName: String): File? {
        val id = scriptName.trim().removeSuffix(".kts").replace('\\', '/')
        // A direct one-script reload can be the first command after a file was created or deleted.
        scriptIndex.invalidate()
        val file = File(scriptsFolder, "$id.kts")
        if (!KSLScriptPathPolicy.isSafeChild(scriptsFolder, file)) {
            logger.warning("${KSLError.SCRIPT_PATH_ESCAPE.tag} Имя скрипта '$scriptName' выходит за пределы scripts/ или использует символическую ссылку")
            return null
        }
        if (!file.isFile) {
            logger.warning("[$id] ${KSLError.SCRIPT_NOT_FOUND.tag} Скрипт '$id.kts' не найден в scripts/")
            return null
        }
        return file
    }

    fun reloadScript(scriptName: String): Boolean {
        val file = resolveScriptFile(scriptName) ?: return false
        return reloadNow(listOf(file), emptyList()).failed == 0
    }

    // done: true/false for the outcome, null when another reload is running.
    fun reloadScriptAsync(scriptName: String, done: (Boolean?) -> Unit) {
        val file = resolveScriptFile(scriptName)
        if (file == null) {
            done(false)
            return
        }
        runAsyncReload(listOf(file), emptyList()) { summary -> done(summary?.let { it.failed == 0 }) }
    }

    private fun filesInFolder(folder: String): List<File> {
        val clean = folder.trim().replace('\\', '/').trim('/')
        if (clean.isEmpty()) return emptyList()
        return findScriptFiles().filter { scriptId(it).startsWith("$clean/") }
    }

    // "economy" or "economy/": every script in that folder (sub-folders included) in dependency order.
    // Returns null when the folder holds no scripts.
    fun reloadFolder(folder: String): Pair<Int, Int>? {
        val files = filesInFolder(folder)
        if (files.isEmpty()) return null
        val summary = reloadNow(files, emptyList())
        return summary.loaded to summary.failed
    }

    // Returns false (nothing started) when the folder holds no scripts.
    fun reloadFolderAsync(folder: String, done: (Pair<Int, Int>?) -> Unit): Boolean {
        val files = filesInFolder(folder)
        if (files.isEmpty()) return false
        runAsyncReload(files, emptyList()) { summary -> done(summary?.let { it.loaded to it.failed }) }
        return true
    }

    data class ChangeReport(val reloaded: Int, val unloaded: Int, val unchanged: Int, val failed: Int)

    private class Changes(val changed: List<File>, val removed: List<String>, val unchanged: Int)

    private fun detectChanges(): Changes {
        val files = findScriptFiles()
        val present = files.map { scriptId(it) }.toSet()
        val removed = scriptStamps.keys.filter { it !in present }
        val changed = files.filter { scriptStamps[scriptId(it)] != stampOf(it) }
        return Changes(changed, removed, files.size - changed.size)
    }

    // Compiles only new and modified scripts and unloads deleted ones: compiling is the expensive part of a reload.
    fun reloadChanged(): ChangeReport {
        val changes = detectChanges()
        val summary = reloadNow(changes.changed, changes.removed)
        return ChangeReport(summary.loaded, summary.removed, changes.unchanged, summary.failed)
    }

    fun reloadChangedAsync(done: (ChangeReport?) -> Unit) {
        val changes = detectChanges()
        runAsyncReload(changes.changed, changes.removed) { summary ->
            done(summary?.let { ChangeReport(it.loaded, it.removed, changes.unchanged, it.failed) })
        }
    }

    // File watcher mutations share the same reload gate as full and per-script reloads.
    fun removeScript(scriptName: String) {
        reloadNow(emptyList(), listOf(scriptName))
    }

    fun removeScriptAsync(scriptName: String, done: (Boolean?) -> Unit) {
        runAsyncReload(emptyList(), listOf(scriptName)) { summary ->
            done(summary?.let { it.removed > 0 })
        }
    }

    private fun knownScriptNames(): Set<String> =
        (tasksByScript.keys + listenersByScript.keys + commandsByScript.keys + placeholdersByScript.keys +
            scriptsWithGuis + libraryRegistry.scriptNames() + entityManager.scriptNames() + scriptRuntimeScopes.keys + generationManager.scriptIds() + scriptDiagnostics.scriptIds() + loadMillisById.keys + scriptStamps.keys + liveScripts).toSet()

    data class DiagnosticLine(val label: String, val ok: Boolean, val detail: String)

    // The compiler self-test takes seconds on first use; callers on the main thread pass a result computed elsewhere.
    fun runDiagnostics(compiler: Pair<Boolean, String> = scriptEngine.selfTestCompiler(), sandbox: Pair<Boolean, String>? = null): List<DiagnosticLine> {
        val lines = mutableListOf<DiagnosticLine>()

        val dbOk = runCatching {
            database.connection.use { it.isValid(2) }
        }.getOrDefault(false)
        lines += DiagnosticLine("База данных", dbOk, if (dbOk) "соединение живое (${dialect.label})" else "не отвечает — проверь ksl.db")

        val tablesOk = checkDatabaseTables()
        lines += DiagnosticLine("Таблицы БД", tablesOk, if (tablesOk) "ksl_scripts, ksl_persist, ksl_table на месте (${dialect.label})" else "миграции не применились корректно")

        val brokenCommands = verifyTrackedCommands()
        lines += DiagnosticLine(
            "Команды скриптов",
            brokenCommands.isEmpty(),
            if (brokenCommands.isEmpty()) "все зарегистрированы корректно" else "перехвачены другим плагином: ${brokenCommands.joinToString(", ")}"
        )

        val scriptsWritable = runCatching { scriptsFolder.canWrite() }.getOrDefault(false)
        lines += DiagnosticLine("Папка scripts/", scriptsWritable, scriptsFolder.absolutePath)

        val (compilerOk, compilerDetail) = compiler
        lines += DiagnosticLine("Компилятор скриптов", compilerOk, compilerDetail)
        sandbox?.let { (ok, detail) -> lines += DiagnosticLine("Изоляция классов (sandbox)", ok, detail) }

        val queued = dbQueueSize()
        val readQueued = dbQueryQueueSize()
        lines += DiagnosticLine("Очереди БД", queued < dbQueueWarnAt && readQueued < dbQueueWarnAt,
            "запись: $queued, чтение: $readQueued (каждая очередь ограничена 10000 задачами)")

        val commandOk = Bukkit.getCommandMap().getCommand("ksl") != null
        lines += DiagnosticLine("Команда /ksl", commandOk, if (commandOk) "зарегистрирована" else "не найдена в commandMap")

        listOf(
            "PlaceholderAPI" to papiEnabled,
            "LuckPerms" to (luckPerms != null),
            "EssentialsX" to (essentials != null),
            "Vault" to (vault != null),
            "SkinsRestorer" to (skinRestorer != null),
            "WorldEdit" to (worldEdit != null),
            "WorldGuard" to (worldGuard != null),
            "TAB" to (tab != null),
            "AuthMe" to (authMe != null),
            "Skript" to (skript != null)
        ).forEach { (name, present) ->
            // The doctor label says EssentialsX, the plugin itself is registered as Essentials.
            val installed = Bukkit.getPluginManager().getPlugin(if (name == "EssentialsX") "Essentials" else name) != null
            val detail = when {
                present -> "подключен"
                installed -> "плагин найден, но хук не инициализировался — смотри лог при старте"
                else -> "не установлен (опционально)"
            }
            lines += DiagnosticLine(name, present || !installed, detail)
        }

        // Hooks contributed by addons; the built-in ones are listed above.
        val builtinHooks = setOf("luckperms", "essentials", "vault", "skinsrestorer", "worldedit", "worldguard", "tab", "authme", "skript")
        addonManager.hooks().filter { it.hookId.lowercase() !in builtinHooks }.forEach { hook ->
            val enabled = addonManager.isPluginEnabled(hook.pluginName)
            lines += DiagnosticLine(
                "${hook.hookId} (${hook.pluginName})",
                enabled,
                if (enabled) "хук аддона подключен" else "хук зарегистрирован, но плагин ${hook.pluginName} не включён"
            )
        }

        val errorCount = KSLErrors.totalCount()
        lines += DiagnosticLine("Ошибки скриптов", errorCount == 0, if (errorCount == 0) "не зафиксировано" else "$errorCount с последнего старта — см. /ksl errors")

        return lines
    }

    // Registration, completion and unload share one lock so a task cannot be inserted
    // into a fresh owner set while that script is being torn down.
    fun trackTask(
        scriptName: String,
        taskId: Int,
        ownerScope: ru.privateserver.ksl.rt.KSLRuntimeScope = scriptRuntimeScope(scriptName)
    ) {
        var rejected = false
        synchronized(taskLock) {
            val scheduler = Bukkit.getScheduler()
            val ids = tasksByScript.computeIfAbsent(scriptName) { ConcurrentHashMap.newKeySet() }
            val stale = ids.filter { !scheduler.isQueued(it) && !scheduler.isCurrentlyRunning(it) }
            stale.forEach { staleId ->
                ids.remove(staleId)
                runCatching { taskScopeCleanups.remove(staleId)?.close() }
            }
            if (!scheduler.isQueued(taskId) && !scheduler.isCurrentlyRunning(taskId)) {
                if (ids.isEmpty()) tasksByScript.remove(scriptName, ids)
                return
            }
            if (taskId !in ids && ids.size >= maxTasks) {
                scheduler.cancelTask(taskId)
                rejected = true
            } else {
                ids.add(taskId)
                try {
                    taskScopeCleanups[taskId] = ownerScope.onClose {
                        scheduler.cancelTask(taskId)
                        untrackTask(scriptName, taskId)
                    }
                } catch (error: IllegalStateException) {
                    ids.remove(taskId)
                    if (ids.isEmpty()) tasksByScript.remove(scriptName, ids)
                    scheduler.cancelTask(taskId)
                    rejected = true
                }
            }
        }
        if (rejected && limitWarned.add("$scriptName|T")) {
            logger.severe("[$scriptName] ${KSLError.SCRIPT_LIMIT_TASKS.tag} Задача отклонена: превышен лимит или runtime generation уже закрыт (security.limits.max-tasks-per-script)")
        }
    }

    fun untrackTask(scriptName: String, taskId: Int) {
        val cleanup = synchronized(taskLock) {
            val ids = tasksByScript[scriptName] ?: return
            if (!ids.remove(taskId)) return
            if (ids.isEmpty()) tasksByScript.remove(scriptName, ids)
            taskScopeCleanups.remove(taskId)
        }
        runCatching { cleanup?.close() }
    }

    /** Cancels only a Bukkit task registered by the requesting script. */
    internal fun cancelTrackedTask(scriptName: String, taskId: Int): Boolean {
        val cleanup = synchronized(taskLock) {
            val ids = tasksByScript[scriptName] ?: return false
            if (!isTaskOwnedBy(scriptName, taskId, tasksByScript)) return false
            ids.remove(taskId)
            if (ids.isEmpty()) tasksByScript.remove(scriptName, ids)
            taskScopeCleanups.remove(taskId)
        }
        runCatching { cleanup?.close() }
        Bukkit.getScheduler().cancelTask(taskId)
        return true
    }

    fun trackGui(scriptName: String, gui: KSLGuiInstance) {
        scriptsWithGuis.add(scriptName)
        guiManager.track(gui)
    }

    fun trackListener(
        scriptName: String,
        listener: Listener,
        ownerScope: ru.privateserver.ksl.rt.KSLRuntimeScope = scriptRuntimeScope(scriptName)
    ) {
        // Keep the per-script limit check and registration atomic with unloadScript().
        val accepted = synchronized(listenerLock) {
            val listeners = listenersByScript.computeIfAbsent(scriptName) { mutableListOf() }
            if (listeners.size >= maxListeners) {
                false
            } else {
                listeners.add(listener)
                true
            }
        }
        if (!accepted) {
            // The listener is already registered with Bukkit; take it back out.
            HandlerList.unregisterAll(listener)
            if (limitWarned.add("$scriptName|L")) {
                logger.severe("[$scriptName] ${KSLError.SCRIPT_LIMIT_LISTENERS.tag} У скрипта уже $maxListeners обработчиков событий — новые отклоняются (security.limits.max-listeners-per-script)")
            }
            return
        }

        try {
            ownerScope.onClose { releaseTrackedListener(scriptName, listener) }
        } catch (error: IllegalStateException) {
            // A stale context must not attach its listener to the replacement generation.
            releaseTrackedListener(scriptName, listener)
            throw error
        }
    }

    private fun releaseTrackedListener(scriptName: String, listener: Listener) {
        synchronized(listenerLock) {
            listenersByScript.computeIfPresent(scriptName) { _, listeners ->
                listeners.remove(listener)
                if (listeners.isEmpty()) null else listeners
            }
        }
        HandlerList.unregisterAll(listener)
    }

    private val commandSyncQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    // Clients keep their own command tree: without a resend, removed commands stay visible and new ones lack tab-complete.
    // Coalesced into one pass per tick; skipped while disabling because the scheduler refuses new tasks then.
    private fun scheduleCommandSync() {
        if (!isEnabled || !commandSyncQueued.compareAndSet(false, true)) return
        try {
            Bukkit.getScheduler().runTask(this, Runnable {
                commandSyncQueued.set(false)
                Bukkit.getOnlinePlayers().forEach { runCatching { it.updateCommands() } }
            })
        } catch (ex: Exception) {
            commandSyncQueued.set(false)
        }
    }

    private fun trackedCommandCount(scriptName: String): Int =
        synchronized(commandLock) { commandsByScript[scriptName]?.size ?: 0 }

    fun trackCommand(
        scriptName: String,
        command: DynamicCommand,
        ownerScope: ru.privateserver.ksl.rt.KSLRuntimeScope = scriptRuntimeScope(scriptName)
    ) {
        // A small array-backed owner list is cheaper than a separate ConcurrentHashMap per script.
        synchronized(commandLock) {
            val owned = commandsByScript.getOrPut(scriptName) { ArrayList(1) }
            if (owned.none { it === command }) owned.add(command)
        }
        scheduleCommandSync()

        try {
            ownerScope.onClose { releaseTrackedCommand(scriptName, command) }
        } catch (error: IllegalStateException) {
            releaseTrackedCommand(scriptName, command)
            throw error
        }

        for (label in sequenceOf(command.name).plus(command.aliases)) {
            if (Bukkit.getCommandMap().getCommand(label) !== command) {
                val fallbackLabel = "${name.lowercase()}:$label"
                val fallbackRegistered = Bukkit.getCommandMap().getCommand(fallbackLabel) === command
                logger.warning(
                    "${KSLError.COMMAND_CONFLICT.tag} Метка '/$label' команды из '${command.scriptName}' уже занята" +
                        if (fallbackRegistered) " — команда доступна как '/$fallbackLabel'." else " — Bukkit не зарегистрировал эту метку."
                )
            }
        }
    }

    private fun releaseTrackedCommand(scriptName: String, command: DynamicCommand) {
        synchronized(commandLock) {
            commandsByScript.computeIfPresent(scriptName) { _, commands ->
                commands.removeAll { it === command }
                if (commands.isEmpty()) null else commands
            }
        }
        unregisterCommands(listOf(command))
    }

    fun registerPlaceholder(scriptName: String, key: String, resolver: (OfflinePlayer) -> String?) {
        val normalized = key.lowercase()
        val owner = "script:$scriptName"
        val previousOwner = placeholders.ownerOf(normalized)
        if (placeholders.contains(normalized) && previousOwner != owner) {
            logger.warning("[$scriptName] ${KSLError.PLACEHOLDER_CONFLICT.tag} Плейсхолдер %ksl_$normalized% уже занят (${previousOwner ?: "аддон/хук"}) — будет перезаписан")
        }
        placeholders.put(normalized, resolver, owner)
        placeholdersByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(normalized)
    }
}
