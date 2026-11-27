package ru.privateserver.ksl.script

import ru.privateserver.ksl.CompiledUnit

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder

/**
 * The engine boundary keeps orchestration and metrics independent from the concrete
 * Kotlin scripting host. Production uses ScriptRunner; tests can supply a deterministic backend.
 */
internal interface KSLScriptEngineBackend {
    fun compile(file: File): CompiledUnit
    fun evaluate(unit: CompiledUnit): Boolean
    fun reportFailure(unit: CompiledUnit)
    fun warmUp()
    fun selfTestCompiler(): Pair<Boolean, String>
    fun sandboxProbe(): Pair<Boolean, String>
    fun compileCacheStats(): KSLCompileCacheStats = KSLCompileCacheStats(0, 0, 0, 0, 0)
    fun clearCompileCache(): Int = 0
    fun invalidateCompileCache(scriptId: String): Int = 0
    fun releaseScript(scriptId: String) {}
    fun close() {}
}

internal data class KSLScriptEngineSnapshot(
    val compilations: Long,
    val compilationFailures: Long,
    val compileSuccesses: Long,
    val evaluations: Long,
    val evaluationFailures: Long,
    val evaluationSuccesses: Long,
    val compileNanos: Long,
    val evaluationNanos: Long,
    val lastScriptId: String?,
    val lastFailure: String?,
    val compileCache: KSLCompileCacheStats
) {
    val averageCompileNanos: Long get() = if (compilations == 0L) 0L else compileNanos / compilations
    val averageEvaluationNanos: Long get() = if (evaluations == 0L) 0L else evaluationNanos / evaluations
    val compileSuccessRate: Double get() = if (compilations == 0L) 1.0 else compileSuccesses.toDouble() / compilations
    val evaluationSuccessRate: Double get() = if (evaluations == 0L) 1.0 else evaluationSuccesses.toDouble() / evaluations
}

/**
 * Script Engine 2 facade.
 *
 * It owns compile/evaluate pipeline metrics and delegates Kotlin compiler,
 * classloader and evaluator work to a backend. Compilation remains callable
 * from worker threads; the caller is responsible for evaluating scripts
 * on the Bukkit primary thread.
 */
internal class KSLScriptEngine(private val backend: KSLScriptEngineBackend) {
    private val compilations = LongAdder()
    private val compilationFailures = LongAdder()
    private val compileSuccesses = LongAdder()
    private val evaluations = LongAdder()
    private val evaluationFailures = LongAdder()
    private val evaluationSuccesses = LongAdder()
    private val compileNanos = LongAdder()
    private val evaluationNanos = LongAdder()
    private val lastScriptId = AtomicReference<String?>(null)
    private val lastFailure = AtomicReference<String?>(null)

    fun compile(file: File): CompiledUnit {
        val started = System.nanoTime()
        try {
            val unit = backend.compile(file)
            compilations.increment()
            lastScriptId.set(unit.id)
            if (!unit.ok) {
                compilationFailures.increment()
                rememberFailure(unit.errors.firstOrNull() ?: unit.failure?.name ?: "Compilation failed")
            } else {
                compileSuccesses.increment()
            }
            return unit
        } catch (error: Throwable) {
            compilations.increment()
            compilationFailures.increment()
            rememberFailure(error)
            throw error
        } finally {
            compileNanos.add((System.nanoTime() - started).coerceAtLeast(0L))
        }
    }

    fun evaluate(unit: CompiledUnit): Boolean {
        val started = System.nanoTime()
        evaluations.increment()
        lastScriptId.set(unit.id)
        try {
            val succeeded = backend.evaluate(unit)
            if (!succeeded) {
                evaluationFailures.increment()
                rememberFailure("Evaluation failed for '${unit.id}'")
            } else {
                evaluationSuccesses.increment()
            }
            return succeeded
        } catch (error: Throwable) {
            evaluationFailures.increment()
            rememberFailure(error)
            throw error
        } finally {
            evaluationNanos.add((System.nanoTime() - started).coerceAtLeast(0L))
        }
    }

    fun reportFailure(unit: CompiledUnit) = backend.reportFailure(unit)
    fun warmUp() = backend.warmUp()
    fun selfTestCompiler(): Pair<Boolean, String> = backend.selfTestCompiler()
    fun sandboxProbe(): Pair<Boolean, String> = backend.sandboxProbe()
    fun clearCompileCache(): Int = backend.clearCompileCache()
    fun invalidateCompileCache(scriptId: String): Int = backend.invalidateCompileCache(scriptId)
    fun releaseScript(scriptId: String) = backend.releaseScript(scriptId)
    fun close() = backend.close()

    fun snapshot() = KSLScriptEngineSnapshot(
        compilations.sum(),
        compilationFailures.sum(),
        compileSuccesses.sum(),
        evaluations.sum(),
        evaluationFailures.sum(),
        evaluationSuccesses.sum(),
        compileNanos.sum(),
        evaluationNanos.sum(),
        lastScriptId.get(),
        lastFailure.get(),
        backend.compileCacheStats()
    )

    fun resetMetrics() {
        compilations.reset()
        compilationFailures.reset()
        compileSuccesses.reset()
        evaluations.reset()
        evaluationFailures.reset()
        evaluationSuccesses.reset()
        compileNanos.reset()
        evaluationNanos.reset()
        lastScriptId.set(null)
        lastFailure.set(null)
    }

    private fun rememberFailure(error: Throwable) =
        rememberFailure("${error::class.simpleName}: ${error.message ?: "no message"}")

    private fun rememberFailure(message: String) {
        lastFailure.set(message.take(MAX_FAILURE_LENGTH))
    }

    private companion object {
        const val MAX_FAILURE_LENGTH = 300
    }
}
