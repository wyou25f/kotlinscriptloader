package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// Raw (encoded) values of one table, so repeated reads skip the database round trip through the DB queue.
// Writes go through store(), which also invalidates any read that was in flight at that moment (see fill()).
// When the cache grows past its limit it is simply emptied: a miss costs one query, and no eviction bookkeeping is needed.
class KSLValueCache(private val limit: Int) {

    private val values = ConcurrentHashMap<String, Any>()
    private val version = AtomicLong()

    private companion object {
        // Distinguishes "known to be absent" from "never read".
        val ABSENT = Any()
    }

    fun version(): Long = version.get()

    fun has(key: String): Boolean = values.containsKey(key)

    // The cached string, or null both for a miss and for a known-absent row; check has() first.
    fun value(key: String): String? = values[key] as? String

    // A write: the cache now holds exactly what the database will hold.
    fun store(key: String, raw: String?) {
        version.incrementAndGet()
        put(key, raw)
    }

    // Result of a database read. Skipped if anything was written since seenVersion was taken,
    // otherwise a slow read could overwrite a newer value that was stored while it was running.
    fun fill(key: String, raw: String?, seenVersion: Long) {
        if (version.get() == seenVersion) put(key, raw)
    }

    fun clear() {
        version.incrementAndGet()
        values.clear()
    }

    fun size(): Int = values.size

    private fun put(key: String, raw: String?) {
        if (values.size >= limit) values.clear()
        values[key] = raw ?: ABSENT
    }
}
