package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object KSLErrors {

    fun validPeriod(plugin: KotlinScriptLoaderPlugin, scriptName: String, where: String, requested: Long): Long {
        if (requested >= 1) return requested
        plugin.logger.warning("[$scriptName] ${KSLError.TASK_BAD_PERIOD.tag} $where: period/interval должен быть >= 1 тик, получено $requested — использую 1")
        return 1L
    }

    inline fun <T> hookSafe(hookName: String, default: T, block: () -> T): T =
        hookSafe(hookName, default, KSLError.ADDON_CALLBACK_FAILED, block)

    inline fun <T> hookSafe(hookName: String, default: T, code: KSLError, block: () -> T): T {
        return try {
            block()
        } catch (ex: Throwable) {
            org.bukkit.Bukkit.getLogger().warning("${code.tag} [KSL $hookName] ${ex::class.simpleName}: ${ex.message}")
            default
        }
    }

    data class LastError(val scriptName: String, val where: String, val message: String, val timestamp: Long, val code: KSLError)

    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var last: LastError? = null

    // The lines behind the last compile failure, for /ksl errors.
    @Volatile var lastDetails: List<String> = emptyList()
        private set

    // Every logged error carries a catalog code (see KSLError); SCRIPT_RUNTIME is the generic one.
    fun log(plugin: KotlinScriptLoaderPlugin, scriptName: String, where: String, ex: Throwable, code: KSLError = KSLError.SCRIPT_RUNTIME) {
        val signal = ex as? KSLScriptSignal
        val cause = deepestCause(ex)
        val message = signal?.message ?: "${cause::class.simpleName}: ${cause.message}"
        counters.computeIfAbsent(scriptName) { AtomicInteger(0) }.incrementAndGet()
        last = LastError(scriptName, where, message, System.currentTimeMillis(), code)
        lastDetails = emptyList()

        val severity = when (signal) {
            is KSLFatalScriptError -> "☠ FATAL"
            is KSLScriptError -> "❌ ERROR"
            else -> "❌ Ошибка"
        }
        val line = "[$scriptName] ${code.tag} $severity в $where: $message"
        if (signal is KSLFatalScriptError) {
            plugin.logger.severe(line)
            plugin.stopScriptAfterFatal(scriptName)
        } else {
            plugin.logScriptProblem(line)
        }

        // Scripts in folders are named "folder/name", while compiled class names may contain only the file name.
        val baseName = scriptName.substringAfterLast('/')
        val relevant = cause.stackTrace.filter { frame ->
            frame.fileName?.endsWith(".kts") == true ||
                frame.fileName?.endsWith(".kt") == true ||
                frame.className.contains(baseName, ignoreCase = true)
        }
        if (relevant.isNotEmpty()) {
            relevant.take(3).forEach { frame ->
                val source = frame.fileName ?: frame.className
                val lineNumber = if (frame.lineNumber > 0) "строка ${frame.lineNumber}" else "строка ?"
                val detail = "  → $source, $lineNumber"
                if (signal is KSLFatalScriptError) plugin.logger.severe(detail) else plugin.logScriptProblem(detail)
            }
        } else if (signal == null) {
            cause.stackTrace.firstOrNull()?.let {
                plugin.logScriptProblem("  → ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
            }
        }
        if (signal == null) plugin.logScriptProblem("  ℹ Что это значит: /ksl error ${code.code}")
    }

    // A script that failed to compile (or was refused by the sandbox) is not an exception, but it is still an error to look up later.
    fun recordFailure(scriptName: String, where: String, errors: List<String>, code: KSLError) {
        counters.computeIfAbsent(scriptName) { AtomicInteger(0) }.incrementAndGet()
        last = LastError(scriptName, where, errors.firstOrNull() ?: "ошибка без описания", System.currentTimeMillis(), code)
        lastDetails = errors
    }

    internal fun deepestCause(error: Throwable): Throwable {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current = error
        seen.add(current)
        while (true) {
            val next = current.cause ?: return current
            if (!seen.add(next)) return current
            current = next
        }
    }

    /** Classifies common JDBC/SQLite failures while preserving the caller's operation-specific fallback. */
    fun classifyDatabaseFailure(error: Throwable, fallback: KSLError): KSLError {
        val chain = mutableListOf<Throwable>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            chain += current
            current = current.cause
        }
        val message = chain.mapNotNull { it.message }.joinToString(" ").lowercase(java.util.Locale.ROOT)
        val sql = chain.filterIsInstance<java.sql.SQLException>().firstOrNull()
        val state = sql?.sqlState.orEmpty()
        val vendorCode = sql?.errorCode ?: 0

        return when {
            message.contains("no space left on device") || message.contains("disk is full") || message.contains("sqlite_full") -> KSLError.DB_DISK_FULL
            message.contains("readonly database") || message.contains("read-only database") || message.contains("sqlite_readonly") || state == "25006" -> KSLError.DB_READ_ONLY
            message.contains("connection is not available") || message.contains("pool is exhausted") || message.contains("connection pool exhausted") -> KSLError.DB_POOL_EXHAUSTED
            message.contains("database is locked") || message.contains("sqlite_busy") || message.contains("deadlock") || vendorCode == 1213 || state == "40001" -> KSLError.DB_DEADLOCK
            message.contains("access denied") || message.contains("authentication failed") || message.contains("invalid authorization") || state.startsWith("28") -> KSLError.DB_AUTHENTICATION_FAILED
            state.startsWith("42") -> KSLError.DB_QUERY_SYNTAX
            state.startsWith("23") -> KSLError.DB_CONSTRAINT_VIOLATION
            message.contains("connect timed out") || message.contains("connection timeout") || message.contains("login timeout") -> KSLError.DB_CONNECTION_TIMEOUT
            chain.any { it is java.sql.SQLTimeoutException } || message.contains("query timed out") || message.contains("statement timeout") -> KSLError.DB_QUERY_TIMEOUT
            state.startsWith("08") || chain.any { it is java.sql.SQLTransientConnectionException } || message.contains("connection reset") || message.contains("connection refused") || message.contains("communications link failure") -> KSLError.DB_CONNECTION_LOST
            vendorCode == 1205 -> KSLError.DB_QUERY_TIMEOUT
            else -> fallback
        }
    }

    fun countFor(scriptName: String): Int = counters[scriptName]?.get() ?: 0

    fun totalCount(): Int = counters.values.sumOf { it.get() }

    fun lastError(): LastError? = last

    fun reset() {
        counters.clear()
        last = null
        lastDetails = emptyList()
    }
}
