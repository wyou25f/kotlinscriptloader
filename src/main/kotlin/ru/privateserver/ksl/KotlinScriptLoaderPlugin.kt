package ru.privateserver.ksl

import com.zaxxer.hikari.HikariConfig
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

internal fun isTaskOwnedBy(scriptName: String, taskId: Int, tasksByScript: Map<String, Set<Int>>): Boolean =
    tasksByScript[scriptName]?.contains(taskId) == true

class KotlinScriptLoaderPlugin : JavaPlugin() {

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

    private val dbQueueWarnAt = 5000

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
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            val queued = dbQueueSize()
            val readQueued = dbQueryQueueSize()
            if (queued >= dbQueueWarnAt || readQueued >= dbQueueWarnAt) {
                logger.warning("${KSLError.DB_QUEUE_LARGE.tag} Очереди БД: запись=$queued, чтение=$readQueued — БД не успевает за скриптами")
            }
        }, 600L, 600L)
    }

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

    lateinit var libraryRegistry: KSLLibraryRegistry
        private set

    val cooldownStore = KSLCooldownStore()

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

    private var maxListeners = 500
    private var maxTasks = 2000
    var taskFailureLimit: Int = 3
        private set
    private val limitWarned = ConcurrentHashMap.newKeySet<String>()

    fun isSandboxed(scriptId: String): Boolean = sandboxEnabled && scriptId !in sandboxTrusted

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
        sandboxEnabled = config.getBoolean("sandbox", false)
        sandboxStrict = !config.getString("security.sandbox-level", "strict").equals("text", ignoreCase = true)
        sandboxTrusted = config.getStringList("security.sandbox-trusted").map { it.trim().removeSuffix(".kts") }.toSet()
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

    private lateinit var scriptRunner: ScriptRunner

    lateinit var workScheduler: ru.privateserver.ksl.rt.KSLWorkScheduler
        private set

    private fun initRuntimeWorkers() {
        val workers = config.getInt("runtime.cpu-threads", (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)).coerceIn(1, 64)
        val queueCapacity = config.getInt("runtime.queue-capacity", 256).coerceIn(1, 100_000)
        val perScriptLimit = config.getInt("runtime.max-tasks-per-script", 64).coerceIn(1, 10_000)
        if (::workScheduler.isInitialized) workScheduler.resize(workers)
        else workScheduler = ru.privateserver.ksl.rt.KSLWorkScheduler(workers, queueCapacity, perScriptLimit)
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

    private val scriptDiagnostics = KSLScriptDiagnostics()
    private val scriptRuntimeScopes = ConcurrentHashMap<String, ru.privateserver.ksl.rt.KSLRuntimeScope>()

    /** Script-owned runtime resources are closed automatically on reload/unload. */
    fun scriptRuntimeScope(scriptId: String): ru.privateserver.ksl.rt.KSLRuntimeScope {
        require(scriptId.isNotBlank()) { "scriptId must not be blank" }
        return scriptRuntimeScopes.computeIfAbsent(scriptId) {
            ru.privateserver.ksl.rt.KSLRT.scope("script:$it")
        }
    }

    fun <T> measureScriptExecution(scriptId: String, operation: String, block: () -> T): T =
        scriptDiagnostics.measure(scriptId, operation, block)

    fun executionSnapshots(scriptId: String): List<KSLExecutionSnapshot> =
        scriptDiagnostics.snapshots(scriptId)

    /** Read-only, bounded context collected by the watchdog diagnostics thread. */
    fun watchdogDiagnostics(): List<String> = buildList {
        add("=== KSL RUNTIME CONTEXT ===")
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
        KSLScriptOrder.inspect(indexedScriptFiles(), ::scriptId)

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
            logger.warning("[$ownerLabel] ${KSLError.TEXT_PLACEHOLDER_RESERVED.tag} @$normalized@ встроен в KSL — выбери другое имя")
            return
        }
        val previous = textPlaceholders.ownerOf(normalized)
        if (textPlaceholders.contains(normalized) && previous != owner) {
            logger.warning("[$ownerLabel] ${KSLError.PLACEHOLDER_CONFLICT.tag} Текстовый плейсхолдер @$normalized@ уже занят (${previous ?: "?"}) — будет перезаписан")
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
        saveDefaultConfig()
        startWatchdogDiagnostics()
        loadConfigTextPlaceholders()
        KSLErrors.reset()

        addonManager   = KSLAddonManager(this)
        readSecurityConfig()
        initRuntimeWorkers()
        initCompileThreads()
        scriptRunner   = ScriptRunner(this)

        KSL.init(addonManager)
        KSLEffectShapes.registerDefaults(addonManager.effectShapes)
        KSLEntityAttributes.registerDefaults(addonManager.entityAttributes)

        getCommand("ksl")?.setExecutor(KSLCommandExecutor(this))

        logger.info("$c9$bold▶ KotlinScriptLoader v${description.version} запускается$r")

        val dbOk = initDatabase()
        persistStore = KSLPersistStore(this)
        tableStore = KSLTableStore(this)
        guiManager = KSLGuiManager(this)
        guiManager.register()
        effectManager = KSLEffectManager(this)
        entityManager = KSLEntityManager(this)
        entityManager.register()
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
        logger.info("Скрипты загрузятся в фоне сразу после старта сервера (scripts.startup-load: sync загружает их до конца старта).")
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

    override fun onDisable() {
        shuttingDown.set(true)
        runCatching { watchdogMonitor?.stop() }
        watchdogMonitor = null
        runCatching { scriptWatcher?.stop() }
        runCatching { compilePool.close() }
        logger.info("$c9$bold■ KotlinScriptLoader останавливается...$r")
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
        if (reloadCompileInProgress.get()) logger.warning("Компиляция ещё выполнялась при выключении; её результат будет отброшен")
        runCatching { unloadAllScripts() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при выгрузке скриптов: ${it.message}") }
        runCatching { if (::workScheduler.isInitialized) workScheduler.close() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Не удалось остановить worker scheduler: ${it.message}") }
        runCatching { addonManager.shutdown() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при выгрузке аддонов: ${it.message}") }
        runCatching { papiUnregister?.invoke() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при снятии PlaceholderAPI expansion: ${it.message}") }
        runCatching {
            dbExecutor.shutdown()
            dbQueryExecutor.shutdown()
            if (!dbExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                val dropped = dbExecutor.shutdownNow().size
                if (dropped > 0) logger.warning("${KSLError.DB_WRITES_LOST.tag} DB-очередь не успела завершиться: потеряно записей — $dropped")
            }
            if (!dbQueryExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                val dropped = dbQueryExecutor.shutdownNow().size
                if (dropped > 0) logger.warning("DB read queue did not finish before shutdown: dropped $dropped queries")
            }
        }.onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Не удалось корректно остановить DB-очередь: ${it.message}") }
        runCatching { if (::database.isInitialized) database.close() }
            .onFailure { logger.warning("${KSLError.SYSTEM_SHUTDOWN.tag} Ошибка при закрытии базы данных: ${it.message}") }
        KSL.shutdown()
        logger.info("$c9$bold■ KotlinScriptLoader остановлен$r")
    }

    private fun printStartupSummary(dbOk: Boolean, loaded: Int, failed: Int) {
        fun flag(on: Boolean) = if (on) "${cg}✔ найден$r" else "${ce}○ не найден$r"
        val addonsCount = addonManager.registeredAddons().size
        val servicesCount = addonManager.getServiceKeys().size

        logger.info("$c9${bold}╔══════════════════════════════════════╗$r")
        logger.info("$c9${bold}║$r $cb$bold KotlinScriptLoader $cf v${description.version}$r")
        logger.info("$c9${bold}╠══════════════════════════════════════╣$r")
        logger.info("$c9${bold}║$r ${if (dbOk) cg else cc}База данных     : ${if (dbOk) "${cg}✔ OK$r" else "${cc}✘ ОШИБКА$r"}")
        logger.info("$c9${bold}║$r ${if (failed == 0) cg else ce}Скрипты         : $cf$loaded ${c7}загружено$r$cf, $failed ${c7}с ошибками$r")
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
        logger.info("$c9${bold}║$r ${c7}Discord         : ${if (discord != null) "${cg}✔ ${discord!!.channels().size} канал(ов)$r" else "${ce}○ не настроен$r"}")
        logger.info("$c9${bold}║$r ${c7}Аддоны          : ${if (addonsCount > 0) "${cg}✔ $addonsCount загружено$r" else "${ce}○ нет$r"}")
        logger.info("$c9${bold}║$r ${c7}Сервисы         : ${if (servicesCount > 0) "${cg}✔ $servicesCount зарегистрировано$r" else "${ce}○ нет$r"}")
        logger.info("$c9${bold}║$r ${c7}Sandbox         : ${if (sandboxEnabled) "${cg}✔ включён (${if (sandboxStrict) "strict" else "text"})$r" else "${ce}○ выключен$r"}")
        logger.info("$c9${bold}╚══════════════════════════════════════╝$r")
    }

    // SQLite by default; MySQL/MariaDB when database.type says so (manage it with phpMyAdmin or any SQL client).
    var dialect: KSLDialect = KSLDialect.SQLITE
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
        sslMode = config.getString("database.ssl-mode", "disable") ?: "disable",
        poolSize = config.getInt("database.pool-size", 4)
    )

    private fun initDatabase(): Boolean {
        var settings: KSLDatabaseSettings? = null
        return try {
            val parsed = readDatabaseSettings()
            settings = parsed
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
            logger.info("База данных: ${parsed.describe()}")
            if (applied > 0) logger.info("Применено миграций БД: $applied")
            true
        } catch (ex: Exception) {
            val rootMessage = generateSequence<Throwable>(ex) { it.cause }.last().message ?: ex.message
            logger.severe("${KSLError.DB_INIT_FAILED.tag} Не удалось инициализировать базу данных (${settings?.describe() ?: "настройки не разобраны"}): $rootMessage")
            if (settings?.dialect?.isRemote == true) {
                logger.severe("Проверь host/port/user/password в config.yml, что база '${settings?.name}' создана (phpMyAdmin → Базы данных) и у пользователя есть на неё права.")
            }
            false
        }
    }

    // Present plugin + successful init => the hook is stored in its typed field and published in the hook registry.
    private fun <T : KSLHook> integrate(pluginName: String, label: String, create: () -> T): T? {
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) return null
        val hook = runCatching(create)
            .onFailure { logger.warning("${KSLError.HOOK_INIT_FAILED.tag} $label: инициализация хука не удалась: ${it.message}") }
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
                .onFailure { logger.warning("${KSLError.PAPI_REGISTER_FAILED.tag} PlaceholderAPI: регистрация expansion не удалась: ${it.message}") }

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
                appendLine("fun ItemStack.setTag(key: String, value: String) = Unit")
                appendLine("fun ItemStack.getTag(key: String): String? = null")
                appendLine("fun ItemStack.hasTag(key: String): Boolean = false")
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
                appendLine("class KSLGuiInstance { fun set(slot: Int, item: ItemStack, onClick: ((Player, org.bukkit.event.inventory.InventoryClickEvent) -> Unit)? = null) = Unit; fun clear(slot: Int) = Unit; fun fill(item: ItemStack) = Unit; fun border(item: ItemStack) = Unit; fun allowItemMovement(slot: Int) = Unit; fun onClose(handler: (Player) -> Unit) = Unit; fun <T : Any> paginate(items: List<T>, slots: List<Int>, prevSlot: Int = -1, nextSlot: Int = -1, render: (T) -> ItemStack, onClick: (Player, T) -> Unit) = Unit; fun open(player: Player) = Unit }")
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
                appendLine("class KSLEffectSpec { var count: Int = 1; var offsetX: Double = 0.0; var offsetY: Double = 0.0; var offsetZ: Double = 0.0; var extra: Double = 0.0; var durationTicks: Long = 100L; var intervalTicks: Long = 2L; var data: Any? = null; fun circle(radius: Double, stepDegrees: Double = 15.0) = Unit; fun helix(radius: Double, turns: Double = 3.0, height: Double = 3.0) = Unit; fun lineTo(target: () -> org.bukkit.Location, points: Int = 20) = Unit; fun sphere(radius: Double, points: Int = 60, spinDegreesPerTick: Double = 0.0) = Unit; fun square(size: Double, pointsPerSide: Int = 10) = Unit; fun arc(radius: Double, startDegrees: Double = 0.0, sweepDegrees: Double = 180.0, points: Int = 30) = Unit; fun cylinder(radius: Double, height: Double, pointsPerRing: Int = 30) = Unit; fun cone(radius: Double, height: Double, points: Int = 60) = Unit; fun heart(size: Double = 1.0, points: Int = 80) = Unit; fun point() = Unit; fun custom(points: (Long, org.bukkit.Location) -> List<org.bukkit.util.Vector>) = Unit; fun shape(name: String, vararg params: Pair<String, Any?>) = Unit; fun color(r: Int, g: Int, b: Int, size: Float = 1f) = Unit; fun color(hex: String, size: Float = 1f) = Unit; fun stopWhen(condition: () -> Boolean) = Unit; fun onEnd(block: () -> Unit) = Unit }")
                appendLine("fun org.bukkit.Location.playEffect(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.playEffect(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.Location.effect(particle: org.bukkit.Particle, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun org.bukkit.entity.Entity.effect(particle: org.bukkit.Particle, follow: Boolean = false, builder: KSLEffectSpec.() -> Unit): Int = 0")
                appendLine("fun stopEffect(taskId: Int) = Unit")
                appendLine()
                appendLine("class KSLEntityBuilder { fun equip(template: String, dropChance: Float? = null) = Unit; fun moveTo(location: org.bukkit.Location, speed: Double = 1.0): Boolean = false; fun follow(target: org.bukkit.entity.LivingEntity, speed: Double = 1.0): Boolean = false; fun stopMoving() = Unit; fun lookAt(location: org.bukkit.Location) = Unit; fun aware(on: Boolean = true) = Unit; fun aggressive(on: Boolean = true) = Unit; fun navigation(canFloat: Boolean? = null, canOpenDoors: Boolean? = null, canPassDoors: Boolean? = null) = Unit; fun attribute(name: String, value: Double) = Unit; fun speed(multiplier: Double) = Unit; fun damage(value: Double) = Unit; fun armor(value: Double) = Unit; fun potion(type: org.bukkit.potion.PotionEffectType, seconds: Int = -1, amplifier: Int = 0) = Unit; fun glow(on: Boolean = true) = Unit; fun silent(on: Boolean = true) = Unit; fun invulnerable(on: Boolean = true) = Unit; fun drops(vararg items: ItemStack, exp: Int? = null, keepDefault: Boolean = false) = Unit }")
                appendLine("class KSLEntityBehaviorBuilder { fun onTick(period: Long, initialDelay: Long = 0L, block: (org.bukkit.entity.LivingEntity) -> Unit) = Unit; fun onDeath(block: (org.bukkit.entity.LivingEntity, Player?) -> Unit) = Unit; fun onDamaged(block: (org.bukkit.entity.LivingEntity, org.bukkit.event.entity.EntityDamageEvent) -> Unit) = Unit; fun onAttack(block: (org.bukkit.entity.LivingEntity, org.bukkit.event.entity.EntityDamageByEntityEvent) -> Unit) = Unit }")
                appendLine("class KSLShopkeeperBuilder { var profession: org.bukkit.entity.Villager.Profession = org.bukkit.entity.Villager.Profession.LIBRARIAN; var villagerType: org.bukkit.entity.Villager.Type = org.bukkit.entity.Villager.Type.PLAINS; var level: Int = 5; var experience: Int = 1; var aiEnabled: Boolean = false; var aware: Boolean = false; var invulnerable: Boolean = true; var silent: Boolean = true; var collidable: Boolean = false; var canPickupItems: Boolean = false; var glowing: Boolean = false; var gravity: Boolean = true; fun trade(cost: ItemStack, result: ItemStack, secondCost: ItemStack? = null, maxUses: Int = Int.MAX_VALUE, rewardExperience: Boolean = false, villagerExperience: Int = 0, priceMultiplier: Float = 0f, ignoreDiscounts: Boolean = true) = Unit }")
                appendLine("fun spawnCustomEntities(location: org.bukkit.Location, template: String, amount: Int, perTick: Int = 1, intervalTicks: Long = 1L, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): Int = 0")
                appendLine("fun mob(location: org.bukkit.Location, template: String, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun mobs(location: org.bukkit.Location, template: String, amount: Int, perTick: Int = 1, intervalTicks: Long = 1L, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): Int = 0")
                appendLine("fun spawnShopkeeper(location: org.bukkit.Location, name: String, removeOnReload: Boolean = true, builder: KSLShopkeeperBuilder.() -> Unit): org.bukkit.entity.Villager = throw UnsupportedOperationException()")
                appendLine("fun shopkeeper(location: org.bukkit.Location, name: String, removeOnReload: Boolean = true, builder: KSLShopkeeperBuilder.() -> Unit): org.bukkit.entity.Villager = throw UnsupportedOperationException()")
                appendLine("fun openShopkeeper(player: Player, villager: org.bukkit.entity.Villager, force: Boolean = true): Boolean = false")
                appendLine("fun spawnCustomEntity(location: org.bukkit.Location, template: String, removeOnReload: Boolean = true, builder: KSLEntityBuilder.() -> Unit = {}): org.bukkit.entity.LivingEntity = throw UnsupportedOperationException()")
                appendLine("fun org.bukkit.entity.LivingEntity.behavior(builder: KSLEntityBehaviorBuilder.() -> Unit) = Unit")
            }
            // Rewriting an identical file on every reload makes the IDE re-index the stub for nothing.
            if (!stub.exists() || stub.readText() != generated) {
                stub.writeText(generated)
                logger.info("Сгенерирован .autocomplete.kts для IDE-подсказок")
            }
        }.onFailure { logger.warning("${KSLError.AUTOCOMPLETE_FAILED.tag} Не удалось сгенерировать .autocomplete.kts: ${it.message}") }
    }

    var lastLoadStats: Pair<Int, Int> = 0 to 0
        private set

    private var scriptWatcher: KSLScriptWatcher? = null

    private fun startWatcher() {
        if (!config.getBoolean("scripts.auto-reload", false)) return
        scriptWatcher = runCatching { KSLScriptWatcher(this).also { it.start() } }
            .onFailure { logger.warning("${KSLError.SCRIPT_WATCHER_FAILED.tag} Автоперезагрузка скриптов не запущена: ${it.message}") }
            .getOrNull()
        if (scriptWatcher != null) logger.info("Автоперезагрузка скриптов включена (scripts.auto-reload)")
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

    /** Force a filesystem refresh; used by explicit reload/validation operations. */
    fun findScriptFiles(): List<File> = scriptIndex.refresh().files

    /** Reuse the current immutable index snapshot for status and command suggestions. */
    fun indexedScriptFiles(): List<File> = scriptIndex.snapshot().files

    fun scriptIds(): List<String> = scriptIndex.snapshot().ids

    fun scriptReloadTargets(): List<String> = scriptIndex.snapshot().reloadTargets

    internal fun invalidateScriptIndex() = scriptIndex.invalidate()

    // Last seen state of each script file; lets /ksl reload changed skip everything that did not change.
    private val scriptStamps = ConcurrentHashMap<String, Long>()

    // Scripts whose code is currently registered (listeners, commands, ...).
    private val liveScripts = ConcurrentHashMap.newKeySet<String>()
    private val loadMillisById = ConcurrentHashMap<String, Long>()
    private val reloadBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reloadCompileInProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    private val shuttingDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun stampOf(file: File): Long = file.lastModified() * 31 + file.length()

    fun scriptInfos(): List<KSLScriptInfo> = indexedScriptFiles().map { file ->
        val id = scriptId(file)
        KSLScriptInfo(id, id in liveScripts, loadMillisById[id] ?: -1L, KSLErrors.countFor(id))
    }

    fun compileScript(file: File): CompiledUnit = scriptRunner.compile(file)

    data class ReloadSummary(val loaded: Int, val failed: Int, val removed: Int)

    // Compile phase: no Bukkit API, so it runs on any thread. Several files compile side by side with scripts.compile-threads > 1.
    private fun compileBatch(files: List<File>): Map<String, CompiledUnit> {
        if (compileThreads <= 1 || files.size <= 1) return files.associate { scriptId(it) to scriptRunner.compile(it) }
        val units = compilePool.map(files) { file -> scriptRunner.compile(file) }
        return files.indices.associate { scriptId(files[it]) to units[it] }
    }

    // Swap phase (main thread): the running version is replaced only after the new one compiled,
    // so a typo in a script no longer takes the working version down with it.
    private fun applyUnit(unit: CompiledUnit): Boolean {
        val id = unit.id
        if (!unit.ok) {
            scriptRunner.reportFailure(unit)
            if (id in liveScripts) {
                logger.warning("[$id] ${KSLError.SCRIPT_KEPT_PREVIOUS.tag} Новая версия не скомпилировалась — продолжает работать предыдущая")
            }
            // Recorded so a broken script is not recompiled until it changes.
            scriptStamps[id] = stampOf(unit.file)
            addonManager.publish("ksl.script.failed", KSLScriptEvent(id, unit.compileMillis, unit.errors.firstOrNull() ?: unit.crash?.message))
            val code = unit.failure ?: KSLError.SCRIPT_COMPILE
            notifyAdmins("§c✘ §f$id§7: ${unit.errors.firstOrNull() ?: unit.crash?.message ?: "ошибка"} §8[${code.code}] §7(подробнее: /ksl errors)")
            return false
        }
        val startedAt = System.nanoTime()
        // Always discard the previous runtime first. If evaluation fails after registering some
        // resources, run the same cleanup again so no partial listeners/tasks/commands survive.
        val ok = replaceScriptRuntime(
            unload = { unloadScript(id) },
            evaluate = { scriptRunner.evaluate(unit) }
        )
        if (ok) liveScripts.add(id)
        val totalMs = unit.compileMillis + (System.nanoTime() - startedAt) / 1_000_000
        loadMillisById[id] = totalMs
        scriptStamps[id] = stampOf(unit.file)
        addonManager.publish(if (ok) "ksl.script.loaded" else "ksl.script.failed", KSLScriptEvent(id, totalMs, if (ok) null else "ошибка выполнения"))
        if (!ok) notifyAdmins("§c✘ §f$id§7: ошибка при выполнении, подробности в консоли §8[${KSLError.SCRIPT_RUNTIME.code}] §7(/ksl errors)")
        return ok
    }

    private fun swap(ordered: List<File>, units: Map<String, CompiledUnit>, removed: Collection<String>): ReloadSummary {
        removed.forEach { unloadScript(it) }
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
                    runCatching { scriptRunner.warmUp() }
                    compiled
                } catch (ex: Throwable) {
                    logger.severe("${KSLError.SCRIPT_RUNTIME.tag} Фоновая компиляция не удалась: ${ex::class.simpleName}: ${ex.message}")
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
                    logger.warning("Не удалось передать результат перезагрузки на главный поток: ${ex.message}")
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
    fun selfTestCompiler(): Pair<Boolean, String> = scriptRunner.selfTestCompiler()

    fun sandboxProbe(): Pair<Boolean, String> = scriptRunner.sandboxProbe()

    fun validateAllScripts(): Map<String, List<String>> {
        val validator = ScriptValidator(this)
        return findScriptFiles().associate { scriptId(it) to validator.validate(it) }
    }

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
        logger.warning("${KSLError.DB_DOCTOR_TABLES.tag} [doctor] Проверка таблиц БД не удалась: ${ex.message}")
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

    private fun unloadScript(scriptName: String) {
        if (::workScheduler.isInitialized) runCatching { workScheduler.cancelOwner(scriptName) }
        if (::effectManager.isInitialized) runCatching { effectManager.cleanupForScript(scriptName) }
        scriptStamps.remove(scriptName)
        limitWarned.remove("$scriptName|L")
        limitWarned.remove("$scriptName|T")
        loadMillisById.remove(scriptName)
        scriptDiagnostics.clearScript(scriptName)
        runCatching { scriptRuntimeScopes.remove(scriptName)?.close() }
            .onFailure { logger.warning("[$scriptName] ${KSLError.SCRIPT_UNLOAD_PARTIAL.tag} Не удалось закрыть runtime-ресурсы: ${it.message}") }
        if (liveScripts.remove(scriptName)) addonManager.publish("ksl.script.unloaded", KSLScriptEvent(scriptName))
        runCatching {
            val taskIds = synchronized(taskLock) { tasksByScript.remove(scriptName)?.toList().orEmpty() }
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
            scriptsWithGuis + libraryRegistry.scriptNames() + entityManager.scriptNames() + scriptRuntimeScopes.keys + scriptDiagnostics.scriptIds() + loadMillisById.keys + scriptStamps.keys + liveScripts).toSet()

    data class DiagnosticLine(val label: String, val ok: Boolean, val detail: String)

    // The compiler self-test takes seconds on first use; callers on the main thread pass a result computed elsewhere.
    fun runDiagnostics(compiler: Pair<Boolean, String> = scriptRunner.selfTestCompiler(), sandbox: Pair<Boolean, String>? = null): List<DiagnosticLine> {
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
    fun trackTask(scriptName: String, taskId: Int) {
        var rejected = false
        synchronized(taskLock) {
            val scheduler = Bukkit.getScheduler()
            val ids = tasksByScript.computeIfAbsent(scriptName) { ConcurrentHashMap.newKeySet() }
            ids.removeIf { !scheduler.isQueued(it) && !scheduler.isCurrentlyRunning(it) }
            if (!scheduler.isQueued(taskId) && !scheduler.isCurrentlyRunning(taskId)) {
                if (ids.isEmpty()) tasksByScript.remove(scriptName, ids)
                return
            }
            if (taskId !in ids && ids.size >= maxTasks) {
                scheduler.cancelTask(taskId)
                rejected = true
            } else {
                ids.add(taskId)
            }
        }
        if (rejected && limitWarned.add("$scriptName|T")) {
            logger.severe("[$scriptName] ${KSLError.SCRIPT_LIMIT_TASKS.tag} У скрипта уже $maxTasks активных задач — новые отменяются (security.limits.max-tasks-per-script)")
        }
    }

    fun untrackTask(scriptName: String, taskId: Int) {
        synchronized(taskLock) {
            tasksByScript.computeIfPresent(scriptName) { _, ids ->
                ids.remove(taskId)
                if (ids.isEmpty()) null else ids
            }
        }
    }

    /** Cancels only a Bukkit task registered by the requesting script. */
    internal fun cancelTrackedTask(scriptName: String, taskId: Int): Boolean =
        synchronized(taskLock) {
            val ids = tasksByScript[scriptName] ?: return@synchronized false
            if (!isTaskOwnedBy(scriptName, taskId, tasksByScript)) return@synchronized false
            ids.remove(taskId)
            if (ids.isEmpty()) tasksByScript.remove(scriptName, ids)
            Bukkit.getScheduler().cancelTask(taskId)
            true
        }

    fun trackGui(scriptName: String) {
        scriptsWithGuis.add(scriptName)
    }

    fun trackListener(scriptName: String, listener: Listener) {
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
        }
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

    fun trackCommand(scriptName: String, command: DynamicCommand) {
        // A small array-backed owner list is cheaper than a separate ConcurrentHashMap per script.
        synchronized(commandLock) {
            val owned = commandsByScript.getOrPut(scriptName) { ArrayList(1) }
            if (owned.none { it === command }) owned.add(command)
        }
        scheduleCommandSync()

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
