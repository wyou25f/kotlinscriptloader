package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap

class KSLCooldownStore {

    private val lastUse = ConcurrentHashMap<String, Long>()

    fun tryUse(fullKey: String, seconds: Long): Boolean {
        val now = System.currentTimeMillis()
        val last = lastUse[fullKey]
        if (last != null && now - last < seconds * 1000) return false
        lastUse[fullKey] = now
        return true
    }

    fun remaining(fullKey: String, seconds: Long): Long {
        val last = lastUse[fullKey] ?: return 0
        val passed = (System.currentTimeMillis() - last) / 1000
        return (seconds - passed).coerceAtLeast(0)
    }

    fun reset(fullKey: String) {
        lastUse.remove(fullKey)
    }

    fun clearScript(scriptName: String) {
        lastUse.keys.removeIf { it.startsWith("$scriptName:") }
    }
}
