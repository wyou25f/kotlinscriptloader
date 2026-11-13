package ru.privateserver.ksl.rt

import java.time.Duration
import java.util.LinkedHashMap

data class KSLCacheStats(
    val hits: Long,
    val misses: Long,
    val evictions: Long,
    val size: Int
) {
    val requests: Long get() = hits + misses
    val hitRate: Double get() = if (requests == 0L) 0.0 else hits.toDouble() / requests
}

/**
 * Bounded, thread-safe LRU cache with optional expire-after-write.
 * Zero TTL disables expiration. Loader callbacks run outside the cache lock.
 */
class KSLCache<K : Any, V : Any>(
    val maxSize: Int,
    val expireAfter: Duration = Duration.ZERO
) {
    private data class Entry<V>(val value: V, val writtenAtNanos: Long)

    private val ttlNanos: Long
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(!expireAfter.isNegative) { "expireAfter must not be negative" }
        ttlNanos = try { expireAfter.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
    }

    @Synchronized
    operator fun get(key: K): V? {
        val entry = entries[key]
        if (entry == null) {
            misses++
            return null
        }
        if (isExpired(entry)) {
            entries.remove(key)
            misses++
            return null
        }
        hits++
        return entry.value
    }

    /** Loader runs outside the lock; simultaneous misses may compute more than once. */
    fun getOrPut(key: K, loader: () -> V): V {
        get(key)?.let { return it }
        val loaded = loader()
        synchronized(this) {
            val existing = entries[key]
            if (existing != null && !isExpired(existing)) return existing.value
            if (existing != null) entries.remove(key)
            putLocked(key, loaded)
            return loaded
        }
    }

    /** Descriptive alias for [getOrPut]. */
    fun getOrCompute(key: K, loader: () -> V): V = getOrPut(key, loader)

    @Synchronized
    fun put(key: K, value: V) = putLocked(key, value)

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    @Synchronized
    fun invalidate(key: K): Boolean = entries.remove(key) != null

    /** Alias for [invalidate]. */
    fun remove(key: K): Boolean = invalidate(key)

    @Synchronized
    fun containsKey(key: K): Boolean {
        // LinkedHashMap is access-ordered: using entries[key] here would refresh recency.
        // A membership check must not protect an otherwise least-recently-used entry.
        val entry = entries.entries.firstOrNull { it.key == key }?.value ?: return false
        if (isExpired(entry)) {
            entries.remove(key)
            return false
        }
        return true
    }

    /** Short alias for [containsKey]. */
    fun has(key: K): Boolean = containsKey(key)

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Alias for [clear]. Statistics are intentionally preserved. */
    fun invalidateAll() = clear()

    @Synchronized
    fun size(): Int {
        removeExpired()
        return entries.size
    }

    val currentSize: Int get() = size()

    @Synchronized
    fun keysSnapshot(): List<K> {
        removeExpired()
        return entries.keys.toList()
    }

    @Synchronized
    fun valuesSnapshot(): List<V> {
        removeExpired()
        return entries.values.map { it.value }
    }

    @Synchronized
    fun stats(): KSLCacheStats {
        removeExpired()
        return KSLCacheStats(hits, misses, evictions, entries.size)
    }

    @Synchronized
    fun resetStats() {
        hits = 0L
        misses = 0L
        evictions = 0L
    }

    private fun isExpired(entry: Entry<V>) =
        ttlNanos > 0L && System.nanoTime() - entry.writtenAtNanos >= ttlNanos

    private fun putLocked(key: K, value: V) {
        entries[key] = Entry(value, System.nanoTime())
        while (entries.size > maxSize) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
            evictions++
        }
    }

    private fun removeExpired() {
        if (ttlNanos <= 0L) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value)) iterator.remove()
        }
    }
}
