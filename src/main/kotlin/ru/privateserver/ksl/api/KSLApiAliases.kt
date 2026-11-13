@file:Suppress("unused")

package ru.privateserver.ksl.api

/**
 * Additional concise Kotlin aliases for common addon operations.
 *
 * These are extension functions rather than new KSLAPI members, so they remain
 * binary-compatible with existing addons and do not require an API version bump.
 */
inline fun <reified T : Any> KSLAPI.serviceOrNull(key: String): T? =
    service<T>(key)

fun KSLAPI.hasService(key: String): Boolean =
    getService(key) != null

fun KSLAPI.addons(): List<KSLAddon> =
    registeredAddons()

fun KSLAPI.addon(addonId: String): KSLAddon? =
    findAddon(addonId)

fun KSLAPI.hasAddon(addonId: String): Boolean =
    isAddonLoaded(addonId)

fun KSLAPI.script(scriptId: String): KSLScriptInfo? =
    findScript(scriptId)

fun KSLAPI.reload(scriptId: String): Boolean =
    reloadScript(scriptId)

fun KSLAPI.reloadAll(): Pair<Int, Int> =
    reloadScripts()

fun KSLAPI.reloadAllAsync(done: (Pair<Int, Int>?) -> Unit) =
    reloadScriptsAsync(done)

fun KSLAPI.emit(topic: String, payload: Any? = null): Int =
    publish(topic, payload)

fun KSLAPI.on(topic: String, handler: (Any?) -> Unit): AutoCloseable =
    subscribe(topic, handler)

fun KSLAPI.hasHookRegistered(hookId: String): Boolean =
    hasHook(hookId)

fun KSLAPI.pluginAvailable(pluginName: String): Boolean =
    isPluginEnabled(pluginName)
