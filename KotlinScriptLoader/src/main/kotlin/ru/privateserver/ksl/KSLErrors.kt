package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object KSLErrors {

    fun validPeriod(plugin: KotlinScriptLoaderPlugin, scriptName: String, where: String, requested: Long): Long {
        if (requested >= 1) return requested
        plugin.logger.warning("[$scriptName] $where: period/interval должен быть >= 1 тик, получено $requested — использую 1")
        return 1L
    }

    inline fun <T> hookSafe(hookName: String, default: T, block: () -> T): T {
        return try {
            block()
        } catch (ex: Throwable) {
            org.bukkit.Bukkit.getLogger().warning("[KSL $hookName] ${ex::class.simpleName}: ${ex.message}")
            default
        }
    }

    data class LastError(val scriptName: String, val where: String, val message: String, val timestamp: Long)

    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var last: LastError? = null

    fun log(plugin: KotlinScriptLoaderPlugin, scriptName: String, where: String, ex: Throwable) {
        val cause = ex.cause ?: ex
        counters.computeIfAbsent(scriptName) { AtomicInteger(0) }.incrementAndGet()
        last = LastError(scriptName, where, "${cause::class.simpleName}: ${cause.message}", System.currentTimeMillis())

        plugin.logger.severe("[$scriptName] ❌ Ошибка в $where: ${cause::class.simpleName}: ${cause.message}")
        val relevant = cause.stackTrace.filter { frame ->
            frame.fileName?.endsWith(".kts") == true ||
                frame.className.contains(scriptName, ignoreCase = true)
        }
        if (relevant.isNotEmpty()) {
            relevant.take(5).forEach { frame ->
                val line = if (frame.lineNumber > 0) "строка ${frame.lineNumber}" else "строка ?"
                plugin.logger.severe("  → $scriptName.kts, $line")
            }
        } else {
            cause.stackTrace.firstOrNull()?.let {
                plugin.logger.severe("  → ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})")
            }
        }
    }

    fun countFor(scriptName: String): Int = counters[scriptName]?.get() ?: 0

    fun totalCount(): Int = counters.values.sumOf { it.get() }

    fun lastError(): LastError? = last

    fun reset() {
        counters.clear()
        last = null
    }
}
