package ru.privateserver.ksl

// Name -> value map whose entries may belong to an owner (an addon) and are dropped together with it.
class KSLOwnedRegistry<T : Any> {

    private data class OwnedValue<T : Any>(val value: T, val owner: String?)

    // Keep the value and its owner in one record. Separate maps can become inconsistent
    // when two addons register the same key concurrently.
    private val items = linkedMapOf<String, OwnedValue<T>>()

    @Synchronized
    fun get(key: String): T? = items[key]?.value

    @Synchronized
    fun contains(key: String): Boolean = items.containsKey(key)

    @Synchronized
    fun ownerOf(key: String): String? = items[key]?.owner

    @Synchronized
    fun put(key: String, value: T, owner: String?) {
        items[key] = OwnedValue(value, owner)
    }

    @Synchronized
    fun remove(key: String) {
        items.remove(key)
    }

    /** Removes a resource only when the caller is allowed to manage its current owner. */
    @Synchronized
    internal fun removeOwned(key: String, requester: String?): KSLRegistryRemovalResult {
        val current = items[key] ?: return KSLRegistryRemovalResult.NOT_FOUND
        if (!KSLResourceOwnershipPolicy.mayUnregister(requester, current.owner)) {
            return KSLRegistryRemovalResult.OWNED_BY_OTHER
        }
        items.remove(key)
        return KSLRegistryRemovalResult.REMOVED
    }

    @Synchronized
    fun releaseOwner(owner: String): Int {
        val keys = items.filterValues { it.owner == owner }.keys.toList()
        keys.forEach(items::remove)
        return keys.size
    }

    @Synchronized
    fun keys(): Set<String> = items.keys.toSet()

    @Synchronized
    fun values(): List<T> = items.values.map { it.value }

    @Synchronized
    fun clear() {
        items.clear()
    }
}

internal enum class KSLRegistryRemovalResult {
    REMOVED,
    NOT_FOUND,
    OWNED_BY_OTHER
}
