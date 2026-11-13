package ru.privateserver.ksl.meslib2

/** Script-scoped facade; exports are cleared automatically when their owner unloads. */
class MesLib2 internal constructor(
    private val owner: String,
    private val exportValue: (String, String, Any) -> Unit,
    private val getValue: (String) -> Any?,
    private val ownerOf: (String) -> String?
) {
    /** Version is an opaque label; compatibility is checked by exact match. */
    fun export(namespace: String, name: String, value: Any, version: String = "1.0.0") {
        validate(namespace, "namespace")
        validate(name, "name")
        require(version.isNotBlank()) { "MesLib2 version must not be blank" }
        exportValue(owner, key(namespace, name), MesLib2Entry(value, version))
    }

    fun <T : Any> get(namespace: String, name: String, type: Class<T>, expectedVersion: String? = null): T? {
        validate(namespace, "namespace")
        validate(name, "name")
        val entry = getValue(key(namespace, name)) as? MesLib2Entry ?: return null
        if (expectedVersion != null && entry.version != expectedVersion) return null
        return if (type.isInstance(entry.value)) type.cast(entry.value) else null
    }
    inline fun <reified T : Any> get(namespace: String, name: String, expectedVersion: String? = null): T? =
        get(namespace, name, T::class.java, expectedVersion)

    fun <T : Any> require(namespace: String, name: String, type: Class<T>, expectedVersion: String? = null): T =
        get(namespace, name, type, expectedVersion)
            ?: throw IllegalStateException("MesLib2 entry '${namespace}/${name}' is missing, has an incompatible type, or does not match version '${expectedVersion ?: "*"}'")
    inline fun <reified T : Any> require(namespace: String, name: String, expectedVersion: String? = null): T =
        require(namespace, name, T::class.java, expectedVersion)

    fun info(namespace: String, name: String): MesLib2Info? {
        validate(namespace, "namespace")
        validate(name, "name")
        val key = key(namespace, name)
        val entry = getValue(key) as? MesLib2Entry ?: return null
        return MesLib2Info(namespace, name, entry.version, entry.value.javaClass.name, ownerOf(key))
    }

    private fun key(namespace: String, name: String) = "meslib2:$namespace:$name"
    private fun validate(value: String, part: String) {
        require(value.matches(Regex("[a-z][a-z0-9._-]*"))) { "MesLib2 $part must match [a-z][a-z0-9._-]*: '$value'" }
    }
}
private data class MesLib2Entry(val value: Any, val version: String)
data class MesLib2Info(val namespace: String, val name: String, val version: String, val typeName: String, val owner: String?)
