package ru.privateserver.ksl.rt

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Low-level runtime utility factory.
 *
 * This API intentionally contains independent utilities rather than exposing
 * KSL's internal script loader, compiler or classloader implementation.
 */
object KSLRT {
    /** Hard cap prevents scripts from creating an unbounded number of named profiler instances. */
    const val MAX_PROFILERS: Int = 1024
    private val profilers = ConcurrentHashMap<String, KSLProfiler>()

    @JvmStatic
    @JvmOverloads
    fun <K : Any, V : Any> cache(maxSize: Int, expireAfter: Duration = Duration.ZERO): KSLCache<K, V> =
        KSLCache(maxSize, expireAfter)

    /** Alias for [cache], emphasizing the LRU eviction policy. */
    @JvmStatic
    @JvmOverloads
    fun <K : Any, V : Any> lru(maxSize: Int, expireAfter: Duration = Duration.ZERO): KSLCache<K, V> =
        cache(maxSize, expireAfter)

    @JvmStatic
    /**
     * Returns the shared profiler for [name]. Repeated calls and [measure] use
     * the same instance so measurements remain observable after the block ends.
     */
    @Synchronized
    fun profiler(name: String): KSLProfiler {
        require(name.isNotBlank()) { "Profiler name must not be blank" }
        profilers[name]?.let { return it }
        check(profilers.size < MAX_PROFILERS) {
            "KSL profiler registry is full ($MAX_PROFILERS); remove unused profilers before registering more"
        }
        return profilers.computeIfAbsent(name) { KSLProfiler(it) }
    }

    /** Removes a named profiler and its accumulated statistics. */
    @Synchronized
    fun removeProfiler(name: String): Boolean = profilers.remove(name) != null

    /** Snapshot of currently registered profiler names. */
    fun profilerNames(): Set<String> = profilers.keys.toSet()

    /** Alias for [profiler]. */
    @JvmStatic
    fun profile(name: String): KSLProfiler = profiler(name)

    /** Measures a block and returns its result while recording its timing. */
    inline fun <T> measure(name: String, block: () -> T): T =
        profiler(name).measure(block)
}

/** Backwards-compatible factory name. */
object kslrt {
    @JvmStatic
    @JvmOverloads
    fun <K : Any, V : Any> cache(maxSize: Int, expireAfter: Duration = Duration.ZERO): KSLCache<K, V> =
        KSLRT.cache(maxSize, expireAfter)

    @JvmStatic
    @JvmOverloads
    fun <K : Any, V : Any> lru(maxSize: Int, expireAfter: Duration = Duration.ZERO): KSLCache<K, V> =
        KSLRT.lru(maxSize, expireAfter)

    @JvmStatic
    fun profiler(name: String): KSLProfiler = KSLRT.profiler(name)

    @JvmStatic
    fun profile(name: String): KSLProfiler = KSLRT.profile(name)

    inline fun <T> measure(name: String, block: () -> T): T =
        KSLRT.measure(name, block)
}

/** Short aliases for scripts and Kotlin integrations. */
val rt get() = KSLRT
val runtime get() = KSLRT
