package ru.privateserver.ksl.rt

import java.time.Duration
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet

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
    private val entries = HashMap<K, Entry<V>>(16, 0.75f)
    // LinkedHashSet keeps least-to-most-recent order while retaining O(1)
    // membership/removal. HashMap lookups do not accidentally refresh LRU order.
    private val recency = LinkedHashSet<K>()
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
            removeEntry(key)
            misses++
            return null
        }
        touch(key)
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
                touch(key)
                existing.lastAccessNanos = now
                return existing.value
            }
            if (existing != null) removeEntry(key)
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
            touch(key)
            existing.lastAccessNanos = now
            hits++
            return existing.value
        }
        if (existing != null) removeEntry(key)
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
            touch(key)
            hits++
            entry.lastAccessNanos = now
            entry.value
        } else {
            if (entry != null) removeEntry(key)
            misses++
            null
        }
        val replacement = remapping(key, current)
        if (replacement == null) removeEntry(key) else putLocked(key, replacement, now)
        return replacement
    }

    @Synchronized
    fun getAndPut(key: K, value: V): V? {
        val now = System.nanoTime()
        val entry = entries[key]
        val previous = entry?.takeUnless { isExpired(it, now) }
        if (previous == null) {
            if (entry != null) removeEntry(key)
            misses++
        } else {
            hits++
            touch(key)
        }
        putLocked(key, value, now)
        return previous?.value
    }

    @Synchronized
    fun getAndRemove(key: K): V? {
        val entry = entries[key] ?: run { misses++; return null }
        if (isExpired(entry, System.nanoTime())) {
            removeEntry(key)
            misses++
            return null
        }
        hits++
        removeEntry(key)
        return entry.value
    }

    @Synchronized
    fun replace(key: K, value: V): Boolean {
        val now = System.nanoTime()
        val entry = entries[key] ?: return false
        if (isExpired(entry, now)) {
            removeEntry(key)
            return false
        }
        touch(key)
        entries[key] = Entry(value, now, now)
        return true
    }

    /** Refreshes TTL clocks and LRU position without changing the value. */
    @Synchronized
    fun refresh(key: K): Boolean {
        val now = System.nanoTime()
        val entry = entries[key] ?: return false
        if (isExpired(entry, now)) {
            removeEntry(key)
            return false
        }
        touch(key)
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
            touch(key)
            existing.lastAccessNanos = now
            return existing.value
        }
        if (existing != null) removeEntry(key)
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
    fun invalidate(key: K): Boolean = removeEntry(key) != null
    fun remove(key: K): Boolean = invalidate(key)

    /** Membership checks validate expiry but deliberately do not refresh LRU or access TTL. */
    @Synchronized
    fun containsKey(key: K): Boolean {
        val entry = entries[key] ?: return false
        if (isExpired(entry, System.nanoTime())) {
            removeEntry(key)
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
                recency.remove(entry.key)
                removed++
            }
        }
        return removed
    }

    @Synchronized
    fun clear() {
        entries.clear()
        recency.clear()
    }
    fun invalidateAll() = clear()

    @Synchronized
    fun size(): Int { removeExpired(); return entries.size }
    val currentSize: Int get() = size()

    @Synchronized
    fun cleanUp(): Int = removeExpired()

    @Synchronized
    fun keysSnapshot(): List<K> { removeExpired(); return recency.toList() }

    @Synchronized
    fun valuesSnapshot(): List<V> {
        removeExpired()
        return recency.mapNotNull { key -> entries[key]?.value }
    }

    /** Point-in-time copy preserving least-to-most-recent LRU order. */
    @Synchronized
    fun entriesSnapshot(): Map<K, V> {
        removeExpired()
        return LinkedHashMap<K, V>(entries.size).also { snapshot ->
            recency.forEach { key -> entries[key]?.let { snapshot[key] = it.value } }
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

    private fun touch(key: K) {
        recency.remove(key)
        recency.add(key)
    }

    private fun removeEntry(key: K): Entry<V>? {
        val removed = entries.remove(key)
        if (removed != null) recency.remove(key)
        return removed
    }

    private fun putLocked(key: K, value: V, now: Long) {
        entries[key] = Entry(value, now, now)
        touch(key)
        while (entries.size > maxSize) {
            val iterator = recency.iterator()
            if (!iterator.hasNext()) break
            val oldestKey = iterator.next()
            iterator.remove()
            entries.remove(oldestKey)
            evictions++
        }
    }

    private fun removeExpired(): Int {
        if (ttlNanos <= 0L && accessTtlNanos <= 0L) return 0
        val now = System.nanoTime()
        var removed = 0
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (isExpired(entry.value, now)) {
                iterator.remove()
                recency.remove(entry.key)
                removed++
            }
        }
        return removed
    }

    private fun durationNanos(duration: Duration): Long =
        try { duration.toNanos() } catch (_: ArithmeticException) { Long.MAX_VALUE }
}
