package ru.privateserver.ksl.api.library
/**
 * Thread-safe script export storage.
 *
 * Ownership is attached to the current value, not to every historical exporter.
 * Replacing a key therefore transfers ownership atomically and unloading the old
 * script cannot remove the replacement.
 */
internal class KSLExportStore(
    private val maxExportsPerOwner: Int = DEFAULT_MAX_EXPORTS_PER_OWNER,
    private val maxExportsTotal: Int = DEFAULT_MAX_EXPORTS_TOTAL,
    private val onOverwrite: (key: String, previousOwner: String?, newOwner: String) -> Unit = { _, _, _ -> }
) {
    companion object {
        const val DEFAULT_MAX_EXPORTS_PER_OWNER = 512
        const val DEFAULT_MAX_EXPORTS_TOTAL = 8192
        private const val MAX_OWNER_LENGTH = 512
        private const val MAX_KEY_LENGTH = 320
    }

    private val lock = Any()
    private val exports = linkedMapOf<String, Any>()
    private val ownerByKey = linkedMapOf<String, String>()
    private val keysByOwner = linkedMapOf<String, MutableSet<String>>()

    init {
        require(maxExportsPerOwner > 0) { "maxExportsPerOwner must be positive" }
        require(maxExportsTotal > 0) { "maxExportsTotal must be positive" }
    }

    fun export(owner: String, key: String, value: Any) {
        validate(owner, key)
        synchronized(lock) { putLocked(owner, key, value) }
    }

    /** Publishes only when the key is currently absent; check and insert are atomic. */
    fun exportIfAbsent(owner: String, key: String, value: Any): Boolean {
        validate(owner, key)
        return synchronized(lock) {
            if (key in exports) false else {
                putLocked(owner, key, value)
                true
            }
        }
    }

    private fun putLocked(owner: String, key: String, value: Any) {
        val exists = key in exports
        val previousOwner = ownerByKey[key]

        // Replacing an existing value from the same owner does not consume another slot.
        if (previousOwner != owner) {
            check((keysByOwner[owner]?.size ?: 0) < maxExportsPerOwner) {
                "Script '$owner' reached the export limit ($maxExportsPerOwner entries)"
            }
        }
        if (!exists) {
            check(exports.size < maxExportsTotal) {
                "KSL export registry reached its global limit ($maxExportsTotal entries)"
            }
        }

        if (exists && previousOwner != owner) {
            onOverwrite(key, previousOwner, owner)
        }

        if (previousOwner != null && previousOwner != owner) {
            keysByOwner[previousOwner]?.let { keys ->
                keys.remove(key)
                if (keys.isEmpty()) keysByOwner.remove(previousOwner)
            }
        }

        exports[key] = value
        ownerByKey[key] = owner
        keysByOwner.getOrPut(owner) { linkedSetOf() }.add(key)
    }

    /** Removes a key only when it is still owned by [owner]. */
    fun removeOwned(owner: String, key: String): Boolean = synchronized(lock) {
        if (ownerByKey[key] != owner) return@synchronized false
        exports.remove(key)
        ownerByKey.remove(key)
        keysByOwner[owner]?.let { keys ->
            keys.remove(key)
            if (keys.isEmpty()) keysByOwner.remove(owner)
        }
        true
    }

    fun get(key: String): Any? = synchronized(lock) { exports[key] }

    fun ownerOf(key: String): String? = synchronized(lock) { ownerByKey[key] }

    fun exportedKeys(): Set<String> = synchronized(lock) { exports.keys.toSet() }

    fun scriptNames(): Set<String> = synchronized(lock) { keysByOwner.keys.toSet() }

    fun clearOwner(owner: String) {
        synchronized(lock) {
            val keys = keysByOwner.remove(owner) ?: return
            keys.forEach { key ->
                if (ownerByKey[key] == owner) {
                    exports.remove(key)
                    ownerByKey.remove(key)
                }
            }
        }
    }

    private fun validate(owner: String, key: String) {
        require(owner.isNotBlank()) { "Export owner must not be blank" }
        require(owner.length <= MAX_OWNER_LENGTH) { "Export owner is too long (max $MAX_OWNER_LENGTH characters)" }
        require(key.isNotBlank()) { "Export key must not be blank" }
        require(key.length <= MAX_KEY_LENGTH) { "Export key is too long (max $MAX_KEY_LENGTH characters)" }
    }
}
