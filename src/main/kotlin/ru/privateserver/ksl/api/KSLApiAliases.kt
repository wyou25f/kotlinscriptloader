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

/** Typed nullable lookup for optional integration hooks. */
inline fun <reified T : KSLHook> KSLAPI.hookOrNull(hookId: String): T? =
    getHook(hookId, T::class.java)

/** Typed required lookup for an integration hook. */
inline fun <reified T : KSLHook> KSLAPI.hookOrFail(hookId: String): T =
    hookOrNull<T>(hookId) ?: error("KSL hook '$hookId' is not registered")

fun KSLAPI.addonOrNull(addonId: String): KSLAddon? = findAddon(addonId)
fun KSLAPI.addonOrFail(addonId: String): KSLAddon = requireAddon(addonId)
fun KSLAPI.scriptOrNull(scriptId: String): KSLScriptInfo? = findScript(scriptId)
fun KSLAPI.scriptOrFail(scriptId: String): KSLScriptInfo = requireScript(scriptId)
fun KSLAPI.isScriptActive(scriptId: String): Boolean = isScriptLoaded(scriptId)
fun KSLAPI.reloadOne(scriptId: String): Boolean = reloadScript(scriptId)
fun KSLAPI.reloadEverything(): Pair<Int, Int> = reloadScripts()
fun KSLAPI.sendEvent(topic: String, payload: Any? = null): Int = publish(topic, payload)
fun KSLAPI.listenTo(topic: String, handler: (Any?) -> Unit): AutoCloseable = subscribe(topic, handler)
fun KSLAPI.hasPlugin(pluginName: String): Boolean = isPluginEnabled(pluginName)

/** Convenience access to the optional built-in Skript integration. */
fun KSLAPI.skriptOrNull(): KSLSkriptHook? =
    hookOrNull("skript")

/** Required Skript hook lookup with a descriptive error if Skript is unavailable. */
fun KSLAPI.requireSkript(): KSLSkriptHook =
    hookOrFail("skript")

fun KSLAPI.isSkriptEnabled(): Boolean =
    skriptOrNull()?.isAvailable == true

fun KSLAPI.skriptCommand(sender: org.bukkit.command.CommandSender, command: String): Boolean =
    skriptOrNull()?.executeCommand(sender, command) == true

fun KSLAPI.skriptCommand(command: String): Boolean =
    skriptOrNull()?.executeConsoleCommand(command) == true

fun KSLAPI.skriptVariable(name: String): Any? =
    skriptOrNull()?.getVariable(name)

fun KSLAPI.setSkriptVariable(name: String, value: Any?): Boolean =
    skriptOrNull()?.setVariable(name, value) == true

fun KSLAPI.deleteSkriptVariable(name: String): Boolean =
    skriptOrNull()?.deleteVariable(name) == true

fun KSLAPI.skriptVariables(name: String): Map<String, Any?>? =
    skriptOrNull()?.getVariableMap(name)

fun KSLAPI.skriptScripts(): Set<String> =
    skriptOrNull()?.loadedScripts().orEmpty()

fun KSLAPI.isSkriptScriptLoaded(path: String): Boolean =
    skriptOrNull()?.isScriptLoaded(path) == true

fun KSLAPI.reloadSkriptScript(path: String): Boolean =
    skriptOrNull()?.requestReloadScript(path) == true

fun KSLAPI.reloadAllSkript(): Boolean =
    skriptOrNull()?.requestReloadAll() == true
