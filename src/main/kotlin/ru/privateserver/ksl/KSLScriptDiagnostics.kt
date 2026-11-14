package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

data class KSLExecutionSnapshot(
    val operation: String,
    val calls: Long,
    val failures: Long,
    val totalNanos: Long,
    val maxNanos: Long,
    val lastFailure: String?
) {
    val averageNanos: Long get() = if (calls == 0L) 0L else totalNanos / calls
}

/**
 * Lightweight per-script execution diagnostics. It records KSL-managed callbacks,
 * not arbitrary Kotlin statements or work launched directly through Bukkit APIs.
 */
class KSLScriptDiagnostics {
    private class Metric {
        val calls = LongAdder()
        val failures = LongAdder()
        val totalNanos = LongAdder()
        val maxNanos = AtomicLong()
        @Volatile var lastFailure: String? = null
    }

    private val scripts = ConcurrentHashMap<String, ConcurrentHashMap<String, Metric>>()

    fun <T> measure(scriptId: String, operation: String, block: () -> T): T {
        val metrics = scripts.computeIfAbsent(scriptId) { ConcurrentHashMap() }
        val key = if (metrics.containsKey(operation) || metrics.size < MAX_OPERATIONS) operation else "other"
        val metric = metrics.computeIfAbsent(key) { Metric() }
        val started = System.nanoTime()
        var failure: Throwable? = null
        try {
            return block()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val elapsed = (System.nanoTime() - started).coerceAtLeast(0L)
            metric.calls.increment()
            metric.totalNanos.add(elapsed)
            metric.maxNanos.accumulateAndGet(elapsed) { current, measured -> maxOf(current, measured) }
            failure?.let {
                metric.failures.increment()
                metric.lastFailure = "${it::class.simpleName}: ${it.message ?: "без сообщения"}".take(300)
            }
        }
    }

    /** Drops all execution history for an unloaded script. */
    fun clearScript(scriptId: String): Boolean = scripts.remove(scriptId) != null

    /** Compatibility alias for [clearScript]. */
    fun removeScript(scriptId: String): Boolean = clearScript(scriptId)

    fun clearAll(): Int {
        val removed = scripts.size
        scripts.clear()
        return removed
    }

    val trackedScripts: Int get() = scripts.size

    fun snapshots(scriptId: String): List<KSLExecutionSnapshot> =
        scripts[scriptId]?.entries?.map { (name, metric) ->
            KSLExecutionSnapshot(
                name,
                metric.calls.sum(),
                metric.failures.sum(),
                metric.totalNanos.sum(),
                metric.maxNanos.get(),
                metric.lastFailure
            )
        }?.sortedByDescending { it.totalNanos } ?: emptyList()

    companion object {
        private const val MAX_OPERATIONS = 128
    }
}

data class KSLResourceSnapshot(
    val scriptId: String,
    val loaded: Boolean,
    val listeners: Int,
    val tasks: Int,
    val commands: Int,
    val placeholders: Int,
    val textPlaceholders: Int,
    val hasGui: Boolean,
    val hasEntities: Boolean
) {
    val total: Int get() = listeners + tasks + commands + placeholders + textPlaceholders +
        (if (hasGui) 1 else 0) + (if (hasEntities) 1 else 0)
    val suspectedLeak: Boolean get() = !loaded && total > 0
}
