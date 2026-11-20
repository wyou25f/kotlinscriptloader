package ru.privateserver.ksl.script
import java.util.HashMap
import java.util.LinkedHashMap
import kotlin.script.experimental.api.CompiledScript

internal data class KSLCompileCacheKey(val scriptId: String, val fingerprint: String)

internal data class KSLCompileCacheValue(
    val compiled: CompiledScript,
    val warnings: List<String>
)

internal data class KSLCompileCacheStats(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val size: Int,
    val maxSize: Int
) {
    val requests: Long get() = hits + misses
    val hitRate: Double get() = if (requests == 0L) 0.0 else hits.toDouble() / requests
}

/**
 * Bounded LRU for successfully compiled scripts. Keys include the source and
 * compilation-environment fingerprint; failures are never cached.
 */
internal class KSLCompiledScriptCache<V : Any>(private val maxSize: Int) {
    private val entries = LinkedHashMap<KSLCompileCacheKey, V>(16, 0.75f, true)
    // A script has at most one live fingerprint. Keep a reverse index so replacing
    // a script version and invalidating it do not scan the entire LRU.
    private val keyByScript = HashMap<String, KSLCompileCacheKey>()
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    init {
        require(maxSize > 0) { "maxSize must be positive" }
    }

    @Synchronized
    fun get(key: KSLCompileCacheKey): V? {
        val value = entries[key]
        if (value == null) misses++ else hits++
        return value
    }

    /** Replaces older fingerprints for the same script to avoid retaining stale compiled classloaders. */
    @Synchronized
    fun put(key: KSLCompileCacheKey, value: V) {
        val previousKey = keyByScript.put(key.scriptId, key)
        if (previousKey != null && previousKey != key) {
            entries.remove(previousKey)
        }
        entries[key] = value
        while (entries.size > maxSize) {
            val eldest = entries.entries.iterator()
            if (!eldest.hasNext()) break
            val evictedKey = eldest.next().key
            eldest.remove()
            if (keyByScript[evictedKey.scriptId] == evictedKey) {
                keyByScript.remove(evictedKey.scriptId)
            }
            evictions++
        }
    }

    @Synchronized
    fun invalidateScript(scriptId: String): Int {
        val key = keyByScript.remove(scriptId) ?: return 0
        return if (entries.remove(key) != null) 1 else 0
    }

    @Synchronized
    fun clear(): Int {
        val removed = entries.size
        entries.clear()
        keyByScript.clear()
        return removed
    }

    @Synchronized
    fun stats(): KSLCompileCacheStats = KSLCompileCacheStats(hits, misses, evictions, entries.size, maxSize)
}
