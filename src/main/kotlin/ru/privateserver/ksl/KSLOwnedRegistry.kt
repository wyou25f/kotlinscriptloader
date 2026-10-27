package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap

// Name -> value map whose entries may belong to an owner (an addon) and are dropped together with it.
class KSLOwnedRegistry<T : Any> {

    private val items = ConcurrentHashMap<String, T>()
    private val owners = ConcurrentHashMap<String, String>()

    fun get(key: String): T? = items[key]

    fun contains(key: String): Boolean = items.containsKey(key)

    fun ownerOf(key: String): String? = owners[key]

    // A stale owner would let the previous owner's unload delete the new entry, so it is always overwritten.
    fun put(key: String, value: T, owner: String?) {
        items[key] = value
        if (owner != null) owners[key] = owner else owners.remove(key)
    }

    fun remove(key: String) {
        items.remove(key)
        owners.remove(key)
    }

    fun releaseOwner(owner: String): Int {
        val keys = owners.filterValues { it == owner }.keys.toList()
        keys.forEach { remove(it) }
        return keys.size
    }

    fun keys(): Set<String> = items.keys.toSet()

    fun values(): List<T> = items.values.toList()

    fun clear() {
        items.clear()
        owners.clear()
    }
}
