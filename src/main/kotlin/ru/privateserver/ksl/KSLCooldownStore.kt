package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class KSLCooldownStore {

    private class Entry(val lastUse: Long, val seconds: Long) {
        fun expired(now: Long) = now - lastUse >= seconds * 1000
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val calls = AtomicInteger()

    fun tryUse(fullKey: String, seconds: Long): Boolean {
        val now = System.currentTimeMillis()
        var allowed = false
        // compute() is atomic per key; get-then-put let two threads both pass the check.
        entries.compute(fullKey) { _, old ->
            if (old != null && now - old.lastUse < seconds * 1000) old
            else { allowed = true; Entry(now, seconds) }
        }
        // Per-player keys never repeat once the player is gone; sweep expired ones occasionally.
        if (calls.incrementAndGet() % 1024 == 0) entries.values.removeIf { it.expired(now) }
        return allowed
    }

    fun remaining(fullKey: String, seconds: Long): Long {
        val last = entries[fullKey]?.lastUse ?: return 0
        val passed = (System.currentTimeMillis() - last) / 1000
        return (seconds - passed).coerceAtLeast(0)
    }

    fun reset(fullKey: String) {
        entries.remove(fullKey)
    }

    fun clearScript(scriptName: String) {
        entries.keys.removeIf { it.startsWith("$scriptName:") }
    }
}
