package ru.privateserver.ksl.meslib2

/**
 * Script-scoped facade for MesLib2 exports.
 *
 * Values are owned by the exporting script and disappear when that script unloads.
 * The default namespace is derived from the owner id; explicit namespaces are
 * recommended for public libraries shared by multiple scripts.
 */
class MesLib2 internal constructor(
    private val owner: String,
    private val exportValue: (String, String, Any) -> Unit,
    private val getValue: (String) -> Any?,
    private val ownerOf: (String) -> String?
) {
    /** A stable, normalized namespace derived from the exporting script id. */
    val namespace: String = normalizeNamespace(owner)

    /** Publishes a value under namespace/name. Version is an exact-match label. */
    fun export(namespace: String, name: String, value: Any, version: String = "1.0.0") {
        validate(namespace, "namespace")
        validate(name, "name")
        require(version.isNotBlank()) { "MesLib2 version must not be blank" }
        exportValue(owner, key(namespace, name), MesLib2Entry(value, version))
    }

    /** Publishes into this script's default namespace using version 1.0.0. */
    fun export(name: String, value: Any) = export(namespace, name, value)

    /** Versioned local export; separate name avoids ambiguous positional overloads. */
    fun exportLocal(name: String, value: Any, version: String = "1.0.0") =
        export(namespace, name, value, version)

    /** Alias for export; useful when describing a library's public contract. */
    fun publish(namespace: String, name: String, value: Any, version: String = "1.0.0") =
        export(namespace, name, value, version)

    /** Publishes into this script's default namespace using version 1.0.0. */
    fun publish(name: String, value: Any) = export(namespace, name, value)

    fun publishLocal(name: String, value: Any, version: String = "1.0.0") =
        export(namespace, name, value, version)

    /** Alias for export, often used for service objects. */
    fun provide(namespace: String, name: String, value: Any, version: String = "1.0.0") =
        export(namespace, name, value, version)

    /** Provides a value in this script's default namespace using version 1.0.0. */
    fun provide(name: String, value: Any) = export(namespace, name, value)

    fun provideLocal(name: String, value: Any, version: String = "1.0.0") =
        export(namespace, name, value, version)

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
        get(namespace, name, expectedVersion)

    /** Short alias for typed get. */
    inline fun <reified T : Any> find(namespace: String, name: String, expectedVersion: String? = null): T? =
        get(namespace, name, expectedVersion)

    inline fun <reified T : Any> findLocal(name: String, expectedVersion: String? = null): T? =
        getLocal(name, expectedVersion)

    fun <T : Any> require(namespace: String, name: String, type: Class<T>, expectedVersion: String? = null): T =
        get(namespace, name, type, expectedVersion)
            ?: throw IllegalStateException("MesLib2 entry '${namespace}/${name}' is missing, has an incompatible type, or does not match version '${expectedVersion ?: "*"}'")

    inline fun <reified T : Any> require(namespace: String, name: String, expectedVersion: String? = null): T =
        require(namespace, name, T::class.java, expectedVersion)

    fun <T : Any> requireLocal(name: String, type: Class<T>, expectedVersion: String? = null): T =
        require(namespace, name, type, expectedVersion)

    inline fun <reified T : Any> requireLocal(name: String, expectedVersion: String? = null): T =
        require(namespace, name, expectedVersion)

    /** Alias for require, useful at call sites that read like dependency resolution. */
    inline fun <reified T : Any> resolve(namespace: String, name: String, expectedVersion: String? = null): T =
        require(namespace, name, expectedVersion)

    inline fun <reified T : Any> resolveLocal(name: String, expectedVersion: String? = null): T =
        requireLocal(name, expectedVersion)

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
        val key = key(namespace, name)
        val entry = getValue(key) as? MesLib2Entry ?: return null
        return MesLib2Info(namespace, name, entry.version, entry.value.javaClass.name, ownerOf(key))
    }

    fun infoLocal(name: String): MesLib2Info? = info(namespace, name)

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
)
