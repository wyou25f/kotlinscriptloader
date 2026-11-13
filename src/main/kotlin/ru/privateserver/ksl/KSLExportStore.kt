package ru.privateserver.ksl

/**
 * Thread-safe script export storage.
 *
 * Ownership is attached to the current value, not to every historical exporter.
 * Replacing a key therefore transfers ownership atomically and unloading the old
 * script cannot remove the replacement.
 */
internal class KSLExportStore(
    private val onOverwrite: (key: String, previousOwner: String?, newOwner: String) -> Unit = { _, _, _ -> }
) {
    private val lock = Any()
    private val exports = linkedMapOf<String, Any>()
    private val ownerByKey = linkedMapOf<String, String>()
    private val keysByOwner = linkedMapOf<String, MutableSet<String>>()

    fun export(owner: String, key: String, value: Any) {
        require(owner.isNotBlank()) { "Export owner must not be blank" }
        require(key.isNotBlank()) { "Export key must not be blank" }

        synchronized(lock) {
            val previousOwner = ownerByKey[key]
            if (key in exports && previousOwner != owner) {
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
}
