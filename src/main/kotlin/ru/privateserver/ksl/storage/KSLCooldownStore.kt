package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-key cooldown storage.
 *
 * Uses the monotonic JVM clock: wall-clock adjustments must not shorten or extend
 * a cooldown. TimeUnit conversion saturates at Long.MAX_VALUE instead of overflowing.
 */
class KSLCooldownStore {

    private class Entry(val lastUseNanos: Long, val cooldownNanos: Long) {
        fun expired(nowNanos: Long): Boolean =
            nowNanos - lastUseNanos >= cooldownNanos
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val calls = AtomicInteger()

    fun tryUse(fullKey: String, seconds: Long): Boolean {
        require(seconds >= 0L) { "Cooldown seconds must not be negative" }
        val now = System.nanoTime()
        val cooldownNanos = TimeUnit.SECONDS.toNanos(seconds)
        var allowed = false
        // compute() is atomic per key; get-then-put would let two threads both pass the check.
        entries.compute(fullKey) { _, old ->
            if (old != null && !old.expired(now)) old
            else {
                allowed = true
                Entry(now, cooldownNanos)
            }
        }
        // Per-player keys may never repeat once the player is gone; sweep expired ones occasionally.
        if (calls.incrementAndGet() % 1024 == 0) {
            entries.values.removeIf { it.expired(now) }
        }
        return allowed
    }

    fun remaining(fullKey: String, seconds: Long): Long {
        require(seconds >= 0L) { "Cooldown seconds must not be negative" }
        val entry = entries[fullKey] ?: return 0L
        val durationNanos = TimeUnit.SECONDS.toNanos(seconds)
        val elapsedNanos = (System.nanoTime() - entry.lastUseNanos).coerceAtLeast(0L)
        val remainingNanos = durationNanos - elapsedNanos
        if (remainingNanos <= 0L) return 0L
        // Round up so a positive fraction of a second is never reported as already ready.
        return remainingNanos / 1_000_000_000L +
            if (remainingNanos % 1_000_000_000L == 0L) 0L else 1L
    }

    fun reset(fullKey: String) {
        entries.remove(fullKey)
    }

    fun clearScript(scriptName: String) {
        entries.keys.removeIf { it.startsWith("$scriptName:") }
    }
}
