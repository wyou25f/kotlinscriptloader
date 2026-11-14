package ru.privateserver.ksl.rt

import java.time.Duration
import java.util.LinkedHashMap

/**
 * Owns runtime resources with an explicit lifetime.
 * Resources close in reverse registration order; closing is idempotent.
 */
class KSLRuntimeScope(val name: String) : AutoCloseable {
    private class Cleanup(@Volatile var action: (() -> Unit)?)

    private val lock = Any()
    private val cleanups = ArrayList<Cleanup>()
    private val profilers = LinkedHashMap<String, KSLProfiler>()

    @Volatile private var closed = false

    init {
        require(name.isNotBlank()) { "Runtime scope name must not be blank" }
    }

    val isClosed: Boolean get() = closed
    val ownedResourceCount: Int get() = synchronized(lock) { cleanups.size }
    val profilerNames: Set<String> get() = synchronized(lock) { profilers.keys.toSet() }

    /** Transfers lifecycle responsibility to this scope and returns the resource. */
    fun <T : AutoCloseable> own(resource: T): T {
        registerCleanup { resource.close() }
        return resource
    }

    /**
     * Registers cleanup code. Closing the returned handle unregisters the action
     * without running it, allowing early release of captured objects.
     */
    fun onClose(action: () -> Unit): AutoCloseable = registerCleanup(action)

    private fun registerCleanup(action: () -> Unit): AutoCloseable {
        val cleanup = Cleanup(action)
        val runImmediately = synchronized(lock) {
            if (closed) true else {
                check(cleanups.size < MAX_OWNED_RESOURCES) {
                    "Runtime scope '$name' reached its resource limit ($MAX_OWNED_RESOURCES)"
                }
                cleanups.add(cleanup)
                false
            }
        }
        if (runImmediately) {
            action()
            throw IllegalStateException("Runtime scope '$name' is already closed")
        }
        return AutoCloseable {
            synchronized(lock) {
                cleanups.remove(cleanup)
                cleanup.action = null
            }
        }
    }

    fun <K : Any, V : Any> cache(
        maxSize: Int,
        expireAfter: Duration = Duration.ZERO,
        expireAfterAccess: Duration = Duration.ZERO
    ): KSLCache<K, V> {
        val cache = KSLRT.cache<K, V>(maxSize, expireAfter, expireAfterAccess)
        registerCleanup { cache.clear() }
        return cache
    }

    /** Alias for [cache], emphasizing the eviction policy. */
    fun <K : Any, V : Any> lru(
        maxSize: Int,
        expireAfter: Duration = Duration.ZERO,
        expireAfterAccess: Duration = Duration.ZERO
    ): KSLCache<K, V> = cache(maxSize, expireAfter, expireAfterAccess)

    fun profiler(name: String): KSLProfiler = synchronized(lock) {
        check(!closed) { "Runtime scope '${this.name}' is already closed" }
        require(name.isNotBlank()) { "Profiler name must not be blank" }
        profilers[name]?.let { return@synchronized it }
        check(profilers.size < MAX_PROFILERS_PER_SCOPE) {
            "Runtime scope '$name' reached its profiler limit ($MAX_PROFILERS_PER_SCOPE)"
        }
        KSLProfiler(name).also { profilers[name] = it }
    }

    /** Alias for [profiler]. */
    fun profile(name: String): KSLProfiler = profiler(name)

    inline fun <T> measure(name: String, block: () -> T): T = profiler(name).measure(block)
    inline fun <T> timed(name: String, block: () -> T): T = measure(name, block)

    fun profilerOrNull(name: String): KSLProfiler? = synchronized(lock) { profilers[name] }
    fun removeProfiler(name: String): Boolean = synchronized(lock) { profilers.remove(name) != null }

    fun clearProfilers(): Int = synchronized(lock) {
        val removed = profilers.size
        profilers.clear()
        removed
    }

    fun profileSnapshots(): List<KSLProfileSnapshot> =
        synchronized(lock) { profilers.values.map { it.snapshot() } }

    override fun close() {
        val actions = synchronized(lock) {
            if (closed) return
            closed = true
            profilers.clear()
            cleanups.asReversed().mapNotNull { it.action }.also {
                cleanups.forEach { cleanup -> cleanup.action = null }
                cleanups.clear()
            }
        }

        var failure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                if (failure == null) {
                    failure = IllegalStateException("One or more resources failed to close in runtime scope '$name'", error)
                } else {
                    failure!!.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    companion object {
        const val MAX_OWNED_RESOURCES: Int = 256
        const val MAX_PROFILERS_PER_SCOPE: Int = 128
    }
}
