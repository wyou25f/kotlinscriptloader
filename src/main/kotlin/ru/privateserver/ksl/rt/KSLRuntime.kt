package ru.privateserver.ksl.rt

import java.time.Duration

/** Script-friendly factory for runtime utilities. */
object kslrt {
    @JvmStatic
    @JvmOverloads
    fun <K : Any, V : Any> cache(maxSize: Int, expireAfter: Duration = Duration.ZERO): KSLCache<K, V> =
        KSLCache(maxSize, expireAfter)

    @JvmStatic
    fun profiler(name: String): KSLProfiler = KSLProfiler(name)
}
