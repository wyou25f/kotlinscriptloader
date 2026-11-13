package ru.privateserver.ksl.rt
import java.time.Duration
import java.util.LinkedHashMap

data class KSLCacheStats(val hits: Long, val misses: Long, val evictions: Long, val size: Int)

/** Bounded, thread-safe LRU cache with optional expire-after-write. Zero TTL disables expiration. */
class KSLCache<K : Any, V : Any>(val maxSize: Int, val expireAfter: Duration = Duration.ZERO) {
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

    @Synchronized fun get(key: K): V? {
        val entry = entries[key]
        if (entry == null) { misses++; return null }
        if (isExpired(entry)) { entries.remove(key); misses++; return null }
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

    @Synchronized fun put(key: K, value: V) = putLocked(key, value)
    @Synchronized fun invalidate(key: K): Boolean = entries.remove(key) != null
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun size(): Int { removeExpired(); return entries.size }
    @Synchronized fun stats(): KSLCacheStats {
        removeExpired()
        return KSLCacheStats(hits, misses, evictions, entries.size)
    }

    private fun isExpired(entry: Entry<V>) = ttlNanos > 0L && System.nanoTime() - entry.writtenAtNanos >= ttlNanos
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
        while (iterator.hasNext()) if (isExpired(iterator.next().value)) iterator.remove()
    }
}
