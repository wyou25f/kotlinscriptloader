package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap

class KSLLibraryRegistry(private val plugin: KotlinScriptLoaderPlugin) {

    private val exports = ConcurrentHashMap<String, Any>()
    private val ownerByKey = ConcurrentHashMap<String, String>()
    private val keysByScript = ConcurrentHashMap<String, MutableList<String>>()

    fun export(scriptName: String, key: String, value: Any) {
        val existingOwner = ownerByKey[key]
        if (existingOwner != null && existingOwner != scriptName) {
            plugin.logger.warning("[$scriptName] export('$key') перезаписывает экспорт скрипта '$existingOwner' — используй уникальные ключи")
        }
        exports[key] = value
        ownerByKey[key] = scriptName
        keysByScript.computeIfAbsent(scriptName) { mutableListOf() }.add(key)
    }

    fun get(key: String): Any? = exports[key]

    fun ownerOf(key: String): String? = ownerByKey[key]

    fun exportedKeys(): Set<String> = exports.keys.toSet()

    fun scriptNames(): Set<String> = keysByScript.keys.toSet()

    fun clearScript(scriptName: String) {
        val keys = keysByScript.remove(scriptName) ?: return
        keys.forEach {
            exports.remove(it)
            ownerByKey.remove(it)
        }
    }
}
