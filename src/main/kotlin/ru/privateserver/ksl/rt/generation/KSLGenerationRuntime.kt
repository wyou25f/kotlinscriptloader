package ru.privateserver.ksl.rt.generation

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
    private var initializationInProgress = false

    @Volatile
    var state: KSLGenerationState = KSLGenerationState.CREATED
        private set

    @Volatile
    var failure: String? = null
        private set

    internal fun markCompiled() = transition(KSLGenerationState.CREATED, KSLGenerationState.COMPILED)

    internal fun beginInitialization() {
        synchronized(stateLock) {
            check(state == KSLGenerationState.COMPILED) {
                "Invalid generation transition for $scriptId#$number: expected ${KSLGenerationState.COMPILED}, found $state"
            }
            state = KSLGenerationState.INITIALIZING
            initializationInProgress = true
        }
    }

    internal fun endInitialization() {
        synchronized(stateLock) {
            if (state == KSLGenerationState.INITIALIZING) initializationInProgress = false
        }
    }

    internal fun markActive() {
        synchronized(stateLock) {
            check(state == KSLGenerationState.INITIALIZING) {
                "Cannot activate generation $scriptId#$number from state $state"
            }
            check(!initializationInProgress) {
                "Cannot activate generation $scriptId#$number while its initializer is still running"
            }
            state = KSLGenerationState.ACTIVE
        }
    }

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

    internal fun markFailed(error: Throwable?) {
        synchronized(stateLock) {
            check(state == KSLGenerationState.CREATED ||
                state == KSLGenerationState.COMPILED ||
                state == KSLGenerationState.INITIALIZING) {
                "Cannot fail generation $scriptId#$number from state $state"
            }
            state = KSLGenerationState.FAILED
            initializationInProgress = false
            if (error != null) failure = describeFailure(error)
        }
    }

    internal fun closeFailedScope() {
        try {
            scope.close()
        } catch (closeError: Throwable) {
            rememberFailure(closeError)
        }
    }

    internal fun fail(error: Throwable?) {
        markFailed(error)
        closeFailedScope()
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
        val detail = describeFailure(error)
        synchronized(stateLock) {
            failure = listOfNotNull(failure, detail).joinToString("; ").take(512)
        }
    }

    private fun describeFailure(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .joinToString(" <- ") { cause ->
                "${cause::class.simpleName}: ${cause.message ?: "no message"}"
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
    private val lifecycleLock = Any()
    private val active = ConcurrentHashMap<String, KSLScriptGeneration<T>>()
    private val candidates = ConcurrentHashMap<String, KSLScriptGeneration<T>>()
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val evaluating = ThreadLocal<KSLScriptGeneration<T>?>()

    fun begin(scriptId: String, sourceStamp: String, artifact: T): KSLScriptGeneration<T> {
        require(scriptId.isNotBlank()) { "scriptId must not be blank" }
        return synchronized(lifecycleLock) {
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
            candidates[scriptId] = generation
            generation
        }
    }

    fun <R> withCandidate(generation: KSLScriptGeneration<T>, block: () -> R): R {
        synchronized(lifecycleLock) {
            check(candidates[generation.scriptId] === generation) {
                "Generation ${generation.scriptId}#${generation.number} is not a registered candidate"
            }
            generation.beginInitialization()
        }
        val previous = evaluating.get()
        evaluating.set(generation)
        return try {
            block()
        } finally {
            generation.endInitialization()
            if (previous == null) evaluating.remove() else evaluating.set(previous)
        }
    }

    /**
     * Publishes a successfully initialized generation. The map replacement is
     * the linearization point; cleanup of the retired scope happens afterwards.
     */
    fun activate(generation: KSLScriptGeneration<T>) {
        val previous = synchronized(lifecycleLock) {
            check(candidates[generation.scriptId] === generation) {
                "Generation ${generation.scriptId}#${generation.number} is not a registered candidate"
            }
            generation.markActive()
            check(candidates.remove(generation.scriptId, generation)) {
                "Generation ${generation.scriptId}#${generation.number} was concurrently removed"
            }
            active.put(generation.scriptId, generation)
        }
        previous?.retire()
    }

    fun fail(generation: KSLScriptGeneration<T>, error: Throwable? = null) {
        synchronized(lifecycleLock) {
            check(candidates.remove(generation.scriptId, generation)) {
                "Generation ${generation.scriptId}#${generation.number} is not a registered candidate"
            }
            generation.markFailed(error)
        }
        generation.closeFailedScope()
    }

    /** Removes and closes the active generation, returning its artifact for recovery. */
    fun retireActive(scriptId: String): KSLScriptGeneration<T>? {
        val previous = synchronized(lifecycleLock) { active.remove(scriptId) } ?: return null
        previous.retire()
        return previous
    }

    fun activeGeneration(scriptId: String): KSLScriptGeneration<T>? = active[scriptId]

    fun currentScope(scriptId: String): KSLRuntimeScope? {
        val candidate = evaluating.get()
        if (candidate?.scriptId == scriptId) return candidate.scope
        // Return a closed active scope as well. A closed generation must reject new
        // registrations; falling back to a legacy scope would silently revive it.
        return active[scriptId]?.scope
    }

    fun snapshots(): List<KSLGenerationSnapshot> {
        val activeSnapshot = active.values.map { it.snapshot(active = true) }
        val candidateSnapshot = candidates.values.map { it.snapshot(active = false) }
        return (activeSnapshot + candidateSnapshot).sortedWith(compareBy({ it.scriptId }, { it.generation }))
    }

    fun scriptIds(): Set<String> = active.keys + candidates.keys

    fun closeAll() {
        val (pending, running) = synchronized(lifecycleLock) {
            val pending = candidates.values.toList()
            pending.forEach { generation ->
                if (candidates.remove(generation.scriptId, generation)) {
                    generation.markFailed(IllegalStateException("KSL is shutting down"))
                }
            }
            val running = active.values.toList()
            active.clear()
            pending to running
        }
        pending.forEach { it.closeFailedScope() }
        running.forEach { it.retire() }
    }

    override fun close() = closeAll()
}
