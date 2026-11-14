package ru.privateserver.ksl

class KSLLibraryRegistry(private val plugin: KotlinScriptLoaderPlugin) {

    private val store = KSLExportStore { key, previousOwner, newOwner ->
        plugin.logger.warning(
            "[$newOwner] ${KSLError.LIBRARY_OVERWRITE.tag} export('$key') перезаписывает экспорт скрипта '${previousOwner ?: "?"}' — используй уникальные ключи"
        )
    }

    fun export(scriptName: String, key: String, value: Any) =
        store.export(scriptName, key, value)

    fun exportIfAbsent(scriptName: String, key: String, value: Any): Boolean =
        store.exportIfAbsent(scriptName, key, value)

    fun remove(scriptName: String, key: String): Boolean =
        store.removeOwned(scriptName, key)

    fun keys(namespacePrefix: String): Set<String> =
        store.exportedKeys().filterTo(linkedSetOf()) { it.startsWith(namespacePrefix) }

    fun get(key: String): Any? = store.get(key)

    fun ownerOf(key: String): String? = store.ownerOf(key)

    fun exportedKeys(): Set<String> = store.exportedKeys()

    fun scriptNames(): Set<String> = store.scriptNames()

    fun clearScript(scriptName: String) = store.clearOwner(scriptName)
}
