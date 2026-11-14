package ru.privateserver.ksl.meslib2

/**
 * Script-scoped facade for MesLib2 exports.
 *
 * Canonical operations are export/get/require/contains/info/remove/list.
 * Compatibility aliases remain available for existing scripts.
 * Values are owned by the exporting script and disappear when that script unloads.
 */
class MesLib2 internal constructor(
    private val owner: String,
    private val exportValue: (String, String, Any) -> Unit,
    private val getValue: (String) -> Any?,
    private val ownerOf: (String) -> String?,
    private val removeValue: (String, String) -> Boolean,
    private val exportIfAbsentValue: (String, String, Any) -> Boolean,
    private val exportedKeys: (String) -> Set<String>
) {
    val namespace: String = normalizeNamespace(owner)
    /** Compatibility alias for [namespace]. */
    val scope: String get() = namespace

    /** Publishes a value under namespace/name. Version is an exact-match label. */
    fun export(namespace: String, name: String, value: Any, version: String = "1.0.0") {
        validate(namespace, "namespace")
        validate(name, "name")
        require(version.isNotBlank()) { "MesLib2 version must not be blank" }
        exportValue(owner, key(namespace, name), MesLib2Entry(value, version))
    }

    /** Publishes to this script's default namespace. */
    fun export(name: String, value: Any, version: String = "1.0.0") =
        export(namespace, name, value, version)

    fun exportLocal(name: String, value: Any, version: String = "1.0.0") =
        export(namespace, name, value, version)

    /** Atomically publishes only if no value currently exists at this key. */
    fun exportIfAbsent(namespace: String, name: String, value: Any, version: String = "1.0.0"): Boolean {
        validate(namespace, "namespace")
        validate(name, "name")
        require(version.isNotBlank()) { "MesLib2 version must not be blank" }
        return exportIfAbsentValue(owner, key(namespace, name), MesLib2Entry(value, version))
    }

    fun exportIfAbsentLocal(name: String, value: Any, version: String = "1.0.0"): Boolean =
        exportIfAbsent(namespace, name, value, version)

    /** Removes an export only if this script still owns it. */
    fun remove(namespace: String, name: String): Boolean {
        validate(namespace, "namespace")
        validate(name, "name")
        return removeValue(owner, key(namespace, name))
    }

    fun removeLocal(name: String): Boolean = remove(namespace, name)

    /** Removes every export in a namespace that is still owned by this script. */
    fun clear(namespace: String): Int {
        validate(namespace, "namespace")
        val prefix = "meslib2:$namespace:"
        return exportedKeys(prefix).count { entryKey ->
            val name = entryKey.removePrefix(prefix)
            name.isNotEmpty() && removeValue(owner, entryKey)
        }
    }

    fun clearLocal(): Int = clear(namespace)

    /** Lists immutable metadata snapshots for all currently visible entries in a namespace. */
    fun list(namespace: String): List<MesLib2Info> {
        validate(namespace, "namespace")
        val prefix = "meslib2:$namespace:"
        return exportedKeys(prefix).asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { entryKey ->
                val name = entryKey.removePrefix(prefix)
                if (name.isEmpty()) null else info(namespace, name)
            }
            .sortedBy { it.name }
            .toList()
    }

    fun listLocal(): List<MesLib2Info> = list(namespace)

    fun <T : Any> get(namespace: String, name: String, type: Class<T>, expectedVersion: String? = null): T? {
        validate(namespace, "namespace")
        validate(name, "name")
        val entry = getValue(key(namespace, name)) as? MesLib2Entry ?: return null
        if (expectedVersion != null && entry.version != expectedVersion) return null
        return if (type.isInstance(entry.value)) type.cast(entry.value) else null
    }

    inline fun <reified T : Any> get(namespace: String, name: String, expectedVersion: String? = null): T? =
        get(namespace, name, T::class.java, expectedVersion)
    fun <T : Any> getLocal(name: String, type: Class<T>, expectedVersion: String? = null): T? =
        get(namespace, name, type, expectedVersion)
    inline fun <reified T : Any> getLocal(name: String, expectedVersion: String? = null): T? =
        getLocal(name, T::class.java, expectedVersion)

    fun <T : Any> require(namespace: String, name: String, type: Class<T>, expectedVersion: String? = null): T =
        get(namespace, name, type, expectedVersion)
            ?: throw IllegalStateException("MesLib2 entry '${namespace}/${name}' is missing, has an incompatible type, or does not match version '${expectedVersion ?: "*"}'")

    inline fun <reified T : Any> require(namespace: String, name: String, expectedVersion: String? = null): T =
        require(namespace, name, T::class.java, expectedVersion)
    fun <T : Any> requireLocal(name: String, type: Class<T>, expectedVersion: String? = null): T =
        require(namespace, name, type, expectedVersion)
    inline fun <reified T : Any> requireLocal(name: String, expectedVersion: String? = null): T =
        requireLocal(name, T::class.java, expectedVersion)

    fun contains(namespace: String, name: String, expectedVersion: String? = null): Boolean {
        validate(namespace, "namespace")
        validate(name, "name")
        val entry = getValue(key(namespace, name)) as? MesLib2Entry ?: return false
        return expectedVersion == null || entry.version == expectedVersion
    }
    fun containsLocal(name: String, expectedVersion: String? = null): Boolean =
        contains(namespace, name, expectedVersion)

    fun info(namespace: String, name: String): MesLib2Info? {
        validate(namespace, "namespace")
        validate(name, "name")
        val entryKey = key(namespace, name)
        val entry = getValue(entryKey) as? MesLib2Entry ?: return null
        return MesLib2Info(namespace, name, entry.version, entry.value.javaClass.name, ownerOf(entryKey))
    }
    fun infoLocal(name: String): MesLib2Info? = info(namespace, name)

    // Compatibility aliases. Prefer the canonical methods above in new scripts.
    fun publish(namespace: String, name: String, value: Any, version: String = "1.0.0") = export(namespace, name, value, version)
    fun publish(name: String, value: Any) = export(name, value)
    fun publishLocal(name: String, value: Any, version: String = "1.0.0") = export(namespace, name, value, version)
    fun provide(namespace: String, name: String, value: Any, version: String = "1.0.0") = export(namespace, name, value, version)
    fun provide(name: String, value: Any) = export(name, value)
    fun provideLocal(name: String, value: Any, version: String = "1.0.0") = export(namespace, name, value, version)
    fun put(namespace: String, name: String, value: Any, version: String = "1.0.0") = export(namespace, name, value, version)
    fun put(name: String, value: Any) = export(name, value)
    fun putLocal(name: String, value: Any, version: String = "1.0.0") = export(namespace, name, value, version)
    inline fun <reified T : Any> find(namespace: String, name: String, expectedVersion: String? = null): T? = get(namespace, name, expectedVersion)
    inline fun <reified T : Any> findLocal(name: String, expectedVersion: String? = null): T? = getLocal(name, expectedVersion)
    inline fun <reified T : Any> lookup(namespace: String, name: String, expectedVersion: String? = null): T? = get(namespace, name, expectedVersion)
    inline fun <reified T : Any> lookupLocal(name: String, expectedVersion: String? = null): T? = getLocal(name, expectedVersion)
    inline fun <reified T : Any> resolve(namespace: String, name: String, expectedVersion: String? = null): T = require(namespace, name, expectedVersion)
    inline fun <reified T : Any> resolveLocal(name: String, expectedVersion: String? = null): T = requireLocal(name, expectedVersion)
    inline fun <reified T : Any> mustGet(namespace: String, name: String, expectedVersion: String? = null): T = require(namespace, name, expectedVersion)
    inline fun <reified T : Any> mustGetLocal(name: String, expectedVersion: String? = null): T = requireLocal(name, expectedVersion)
    fun has(namespace: String, name: String, expectedVersion: String? = null): Boolean = contains(namespace, name, expectedVersion)
    fun hasLocal(name: String, expectedVersion: String? = null): Boolean = containsLocal(name, expectedVersion)
    fun describe(namespace: String, name: String): MesLib2Info? = info(namespace, name)
    fun describeLocal(name: String): MesLib2Info? = infoLocal(name)

    private fun key(namespace: String, name: String) = "meslib2:$namespace:$name"

    private fun validate(value: String, part: String) {
        require(value.matches(Regex("[a-z][a-z0-9._-]*"))) {
            "MesLib2 $part must match [a-z][a-z0-9._-]*: '$value'"
        }
    }
}

private fun normalizeNamespace(owner: String): String {
    val normalized = owner.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .ifBlank { "script" }
    return if (normalized.first().isLetter()) normalized else "script-$normalized"
}

private data class MesLib2Entry(val value: Any, val version: String)

data class MesLib2Info(
    val namespace: String,
    val name: String,
    val version: String,
    val typeName: String,
    val owner: String?
) {
    val key: String get() = "$namespace/$name"
}
