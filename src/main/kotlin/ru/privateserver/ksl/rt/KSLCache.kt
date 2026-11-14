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

/** Bounded, thread-safe LRU cache with optional expire-after-write and expire-after-access. */
class KSLCache<K : Any, V : Any> @JvmOverloads constructor(
    val maxSize: Int,
    val expireAfter: Duration = Duration.ZERO,
    val expireAfterAccess: Duration = Duration.ZERO
) {
    private data class Entry<V>(val value: V, var writtenAtNanos: Long, var lastAccessNanos: Long)
    private val ttlNanos: Long
    private val accessTtlNanos: Long
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    init {
        require(maxSize > 0) { "maxSize must be positive" }
        require(!expireAfter.isNegative) { "expireAfter must not be negative" }
        require(!expireAfterAccess.isNegative) { "expireAfterAccess must not be negative" }
        ttlNanos = durationNanos(expireAfter)
        accessTtlNanos = durationNanos(expireAfterAccess)
    }

    @Synchronized
    operator fun get(key: K): V? {
        val entry = entries[key]
        if (entry == null) { misses++; return null }
        val now = System.nanoTime()
        if (isExpired(entry, now)) {
            entries.remove(key)
            misses++
            return null
        }
        entry.lastAccessNanos = now
        hits++
        return entry.value
    }

    /** Loader runs outside the lock; concurrent misses may invoke it more than once. */
    fun getOrPut(key: K, loader: () -> V): V {
        get(key)?.let { return it }
        val loaded = loader()
        synchronized(this) {
            val now = System.nanoTime()
            val existing = entries[key]
            if (existing != null && !isExpired(existing, now)) {
                existing.lastAccessNanos = now
                return existing.value
            }
            if (existing != null) entries.remove(key)
            putLocked(key, loaded, now)
            return loaded
        }
    }

    fun getOrCompute(key: K, loader: () -> V): V = getOrPut(key, loader)

    /** Atomic variant: loader executes under the cache lock, once per missing key. */
    @Synchronized
    fun getOrComputeAtomic(key: K, loader: () -> V): V {
        val now = System.nanoTime()
        val existing = entries[key]
        if (existing != null && !isExpired(existing, now)) {
            existing.lastAccessNanos = now
            hits++
            return existing.value
        }
        if (existing != null) entries.remove(key)
        misses++
        val loaded = loader()
        putLocked(key, loaded, now)
        return loaded
    }

    /** Map-style atomic alias. Keep loader short and non-blocking. */
    fun computeIfAbsent(key: K, loader: () -> V): V = getOrComputeAtomic(key, loader)
    fun getOrPutIfAbsent(key: K, loader: () -> V): V = getOrComputeAtomic(key, loader)

    /** Atomic remapping; a null result removes the key. Callback must be short. */
    @Synchronized
    fun compute(key: K, remapping: (K, V?) -> V?): V? {
        val now = System.nanoTime()
        val entry = entries[key]
        val current = if (entry != null && !isExpired(entry, now)) {
            hits++
            entry.lastAccessNanos = now
            entry.value
        } else {
            if (entry != null) entries.remove(key)
            misses++
            null
        }
        val replacement = remapping(key, current)
        if (replacement == null) entries.remove(key) else putLocked(key, replacement, now)
        return replacement
    }

    @Synchronized
    fun getAndPut(key: K, value: V): V? {
        val now = System.nanoTime()
        val entry = entries[key]
        val previous = entry?.takeUnless { isExpired(it, now) }
        if (previous == null) {
            if (entry != null) entries.remove(key)
            misses++
        } else hits++
        putLocked(key, value, now)
        return previous?.value
    }

    @Synchronized
    fun getAndRemove(key: K): V? {
        val entry = entries[key] ?: run { misses++; return null }
        if (isExpired(entry, System.nanoTime())) {
            entries.remove(key)
            misses++
            return null
        }
        hits++
        entries.remove(key)
        return entry.value
    }

    @Synchronized
    fun replace(key: K, value: V): Boolean {
        val now = System.nanoTime()
        val entry = entries[key] ?: return false
        if (isExpired(entry, now)) {
            entries.remove(key)
            return false
        }
        entries[key] = Entry(value, now, now)
        return true
    }

    /** Refreshes TTL clocks and LRU position without changing the value. */
    @Synchronized
    fun refresh(key: K): Boolean {
        val now = System.nanoTime()
        val entry = entries[key] ?: return false
        if (isExpired(entry, now)) {
            entries.remove(key)
            return false
        }
        entry.writtenAtNanos = now
        entry.lastAccessNanos = now
        return true
    }

    @Synchronized
    fun put(key: K, value: V) = putLocked(key, value, System.nanoTime())

    @Synchronized
    fun putIfAbsent(key: K, value: V): V? {
        val now = System.nanoTime()
        val existing = entries[key]
        if (existing != null && !isExpired(existing, now)) {
            existing.lastAccessNanos = now
            return existing.value
        }
        if (existing != null) entries.remove(key)
        putLocked(key, value, now)
        return null
    }

    @Synchronized
    fun putAll(values: Map<K, V>) {
        val now = System.nanoTime()
        values.forEach { (key, value) -> putLocked(key, value, now) }
    }

    operator fun set(key: K, value: V) = put(key, value)
    fun getOrDefault(key: K, default: V): V = get(key) ?: default

    @Synchronized
    fun invalidate(key: K): Boolean = entries.remove(key) != null
    fun remove(key: K): Boolean = invalidate(key)

    @Synchronized
    fun containsKey(key: K): Boolean {
        val entry = entries.entries.firstOrNull { it.key == key }?.value ?: return false
        if (isExpired(entry, System.nanoTime())) {
            entries.remove(key)
            return false
        }
        return true
    }
    fun has(key: K): Boolean = containsKey(key)

    @Synchronized
    fun containsValue(value: V): Boolean {
        removeExpired()
        return entries.values.any { it.value == value }
    }

    val isEmpty: Boolean get() = size() == 0
    val isNotEmpty: Boolean get() = !isEmpty

    /** Removes entries matching [predicate] and returns the count. */
    @Synchronized
    fun removeIf(predicate: (K, V) -> Boolean): Int {
        removeExpired()
        var removed = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key, entry.value.value)) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    @Synchronized
    fun clear() { entries.clear() }
    fun invalidateAll() = clear()

    @Synchronized
    fun size(): Int { removeExpired(); return entries.size }
    val currentSize: Int get() = size()

    @Synchronized
    fun cleanUp(): Int = removeExpired()

    @Synchronized
    fun keysSnapshot(): List<K> { removeExpired(); return entries.keys.toList() }

    @Synchronized
    fun valuesSnapshot(): List<V> { removeExpired(); return entries.values.map { it.value } }

    /** Point-in-time copy preserving least-to-most-recent LRU order. */
    @Synchronized
    fun entriesSnapshot(): Map<K, V> {
        removeExpired()
        return LinkedHashMap<K, V>(entries.size).also { snapshot ->
            entries.forEach { (key, entry) -> snapshot[key] = entry.value }
        }
    }

    @Synchronized
    fun stats(): KSLCacheStats {
        removeExpired()
        return KSLCacheStats(hits, misses, evictions, entries.size)
    }

    @Synchronized
    fun resetStats() { hits = 0L; misses = 0L; evictions = 0L }

    private fun isExpired(entry: Entry<V>, now: Long): Boolean =
        (ttlNanos > 0L && now - entry.writtenAtNanos >= ttlNanos) ||
            (accessTtlNanos > 0L && now - entry.lastAccessNanos >= accessTtlNanos)

    private fun putLocked(key: K, value: V, now: Long) {
        entries[key] = Entry(value, now, now)
        while (entries.size > maxSize) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
            evictions++
        }
    }

    private fun removeExpired(): Int {
        if (ttlNanos <= 0L && accessTtlNanos <= 0L) return 0
        val now = System.nanoTime()
        var removed = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value, now)) { iterator.remove(); removed++ }
        }
        return removed
    }

    private fun durationNanos(duration: Duration): Long =
        try { duration.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
}
