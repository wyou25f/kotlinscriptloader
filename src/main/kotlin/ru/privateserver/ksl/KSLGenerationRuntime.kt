package ru.privateserver.ksl

import ru.privateserver.ksl.rt.KSLRT
import ru.privateserver.ksl.rt.KSLRuntimeScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle states for one compiled version of a script.
 *
 * A generation owns its runtime scope. Failed candidates are closed without
 * affecting another active generation; retired generations are closed after
 * the replacement has been installed.
 */
internal enum class KSLGenerationState {
    CREATED,
    COMPILED,
    INITIALIZING,
    ACTIVE,
    RETIRED,
    FAILED,
    DISPOSED
}

internal class KSLScriptGeneration<T : Any> internal constructor(
    val scriptId: String,
    val number: Long,
    val sourceStamp: String,
    val artifact: T,
    val scope: KSLRuntimeScope
) {
    private val stateLock = Any()

    @Volatile
    var state: KSLGenerationState = KSLGenerationState.CREATED
        private set

    @Volatile
    var failure: String? = null
        private set

    internal fun markCompiled() = transition(KSLGenerationState.CREATED, KSLGenerationState.COMPILED)

    internal fun beginInitialization() =
        transition(KSLGenerationState.COMPILED, KSLGenerationState.INITIALIZING)

    internal fun markActive() =
        transition(KSLGenerationState.INITIALIZING, KSLGenerationState.ACTIVE)

    internal fun retire() {
        synchronized(stateLock) {
            if (state == KSLGenerationState.DISPOSED) return
            check(state == KSLGenerationState.ACTIVE || state == KSLGenerationState.RETIRED) {
                "Cannot retire generation $scriptId#$number from state $state"
            }
            state = KSLGenerationState.RETIRED
        }
        try {
            scope.close()
        } catch (error: Throwable) {
            rememberFailure(error)
        } finally {
            synchronized(stateLock) { state = KSLGenerationState.DISPOSED }
        }
    }

    internal fun fail(error: Throwable?) {
        synchronized(stateLock) {
            check(state == KSLGenerationState.CREATED ||
                state == KSLGenerationState.COMPILED ||
                state == KSLGenerationState.INITIALIZING) {
                "Cannot fail generation $scriptId#$number from state $state"
            }
            state = KSLGenerationState.FAILED
            if (error != null) failure = "${error::class.simpleName}: ${error.message ?: "no message"}"
        }
        try {
            scope.close()
        } catch (closeError: Throwable) {
            rememberFailure(closeError)
        }
    }

    private fun transition(expected: KSLGenerationState, next: KSLGenerationState) {
        synchronized(stateLock) {
            check(state == expected) {
                "Invalid generation transition for $scriptId#$number: expected $expected, found $state"
            }
            state = next
        }
    }

    private fun rememberFailure(error: Throwable) {
        val detail = "${error::class.simpleName}: ${error.message ?: "no message"}"
        synchronized(stateLock) {
            failure = listOfNotNull(failure, detail).joinToString("; ").take(512)
        }
    }

    internal fun snapshot(active: Boolean) = KSLGenerationSnapshot(
        scriptId = scriptId,
        generation = number,
        state = state,
        sourceStamp = sourceStamp,
        ownedResources = scope.ownedResourceCount,
        failure = failure,
        active = active
    )
}

internal data class KSLGenerationSnapshot(
    val scriptId: String,
    val generation: Long,
    val state: KSLGenerationState,
    val sourceStamp: String,
    val ownedResources: Int,
    val failure: String?,
    val active: Boolean
)

/**
 * Tracks active and initializing script generations.
 *
 * Evaluation is scoped with a ThreadLocal because Kotlin script evaluation is
 * synchronous on the server thread. Async work must capture the generation's
 * scope through BukkitScriptContext rather than looking it up later.
 */
internal class KSLGenerationManager<T : Any>(
    private val scopeFactory: (String) -> KSLRuntimeScope = { KSLRT.scope(it) }
) : AutoCloseable {
    private val active = ConcurrentHashMap<String, KSLScriptGeneration<T>>()
    private val candidates = ConcurrentHashMap<String, KSLScriptGeneration<T>>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val evaluating = ThreadLocal<KSLScriptGeneration<T>?>()

    fun begin(scriptId: String, sourceStamp: String, artifact: T): KSLScriptGeneration<T> {
        require(scriptId.isNotBlank()) { "scriptId must not be blank" }
        check(!candidates.containsKey(scriptId)) { "A generation for '$scriptId' is already initializing" }
        val number = counters.computeIfAbsent(scriptId) { AtomicLong() }.incrementAndGet()
        val generation = KSLScriptGeneration(
            scriptId = scriptId,
            number = number,
            sourceStamp = sourceStamp,
            artifact = artifact,
            scope = scopeFactory("script:$scriptId#generation:$number")
        )
        generation.markCompiled()
        val previous = candidates.putIfAbsent(scriptId, generation)
        if (previous != null) {
            runCatching { generation.scope.close() }
            error("A generation for '$scriptId' is already initializing")
        }
        return generation
    }

    fun <R> withCandidate(generation: KSLScriptGeneration<T>, block: () -> R): R {
        check(candidates[generation.scriptId] === generation) {
            "Generation ${generation.scriptId}#${generation.number} is not a registered candidate"
        }
        generation.beginInitialization()
        val previous = evaluating.get()
        evaluating.set(generation)
        return try {
            block()
        } finally {
            if (previous == null) evaluating.remove() else evaluating.set(previous)
        }
    }

    /**
     * Publishes a successfully initialized generation. The map replacement is
     * the linearization point; cleanup of the retired scope happens afterwards.
     */
    fun activate(generation: KSLScriptGeneration<T>) {
        check(candidates.remove(generation.scriptId, generation)) {
            "Generation ${generation.scriptId}#${generation.number} is not a registered candidate"
        }
        generation.markActive()
        val previous = active.put(generation.scriptId, generation)
        previous?.retire()
    }

    fun fail(generation: KSLScriptGeneration<T>, error: Throwable? = null) {
        candidates.remove(generation.scriptId, generation)
        generation.fail(error)
    }

    /** Removes and closes the active generation, returning its artifact for recovery. */
    fun retireActive(scriptId: String): KSLScriptGeneration<T>? {
        val previous = active.remove(scriptId) ?: return null
        previous.retire()
        return previous
    }

    fun activeGeneration(scriptId: String): KSLScriptGeneration<T>? = active[scriptId]

    fun currentScope(scriptId: String): KSLRuntimeScope? {
        val candidate = evaluating.get()
        if (candidate?.scriptId == scriptId && !candidate.scope.isClosed) return candidate.scope
        return active[scriptId]?.scope?.takeUnless { it.isClosed }
    }

    fun snapshots(): List<KSLGenerationSnapshot> {
        val activeSnapshot = active.values.map { it.snapshot(active = true) }
        val candidateSnapshot = candidates.values.map { it.snapshot(active = false) }
        return (activeSnapshot + candidateSnapshot).sortedWith(compareBy({ it.scriptId }, { it.generation }))
    }

    fun scriptIds(): Set<String> = active.keys + candidates.keys

    fun closeAll() {
        candidates.values.toList().forEach { fail(it, IllegalStateException("KSL is shutting down")) }
        active.keys.toList().forEach(::retireActive)
    }

    override fun close() = closeAll()
}
