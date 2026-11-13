package ru.privateserver.ksl.rt

import java.time.Duration

/**
 * Low-level runtime utility factory.
 *
 * This API intentionally contains independent utilities rather than exposing
 * KSL's internal script loader, compiler or classloader implementation.
 */
object KSLRT {
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
    fun profiler(name: String): KSLProfiler = KSLProfiler(name)

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
