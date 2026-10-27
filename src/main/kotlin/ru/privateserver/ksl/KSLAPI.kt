package ru.privateserver.ksl

import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.java.JavaPlugin

interface KSLAPI {
    val kslPlugin: JavaPlugin

    // Same value as KSL.API_VERSION of the installed KSL.
    val apiVersion: Int

    fun registerAddon(addon: KSLAddon)
    fun unregisterAddon(addonId: String)
    fun registerService(key: String, instance: Any)
    fun unregisterService(key: String)
    fun getService(key: String): Any?

    // Null when the key is missing or the instance is not a [type] (usually a classloader mismatch, which is logged).
    fun <T : Any> getService(key: String, type: Class<T>): T?

    fun addDefaultImports(vararg packages: String)
    fun registerContextExtension(extension: KSLContextExtension)
    fun unregisterContextExtension(extensionId: String)
    fun registeredAddons(): List<KSLAddon>
    fun isAddonLoaded(addonId: String): Boolean
    fun onAddonReady(addonId: String, callback: (KSLAddon) -> Unit)

    // Loose coupling between addons and scripts: publishers need no reference to subscribers.
    // Handlers run synchronously on the publisher's thread; one failing handler does not stop the others.
    // Subscriptions made inside onLoad are removed automatically when the addon unloads.
    fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable

    // Returns how many handlers ran without throwing.
    fun publish(topic: String, payload: Any? = null): Int

    // Main thread only.
    fun reloadScripts(): Pair<Int, Int>

    // ---- Hooks: integrations with other plugins, available to scripts as hook<T>("id") ----

    // Hooks registered inside onLoad are removed together with the addon. Ids are case-insensitive.
    fun registerHook(hook: KSLHook)
    fun unregisterHook(hookId: String)
    fun getHook(hookId: String): KSLHook?
    fun <T : KSLHook> getHook(hookId: String, type: Class<T>): T?
    fun hooks(): List<KSLHook>
    fun isPluginEnabled(pluginName: String): Boolean

    // ---- Engines: plug new behaviour into playEffect / spawnCustomEntity ----

    // Usable in scripts as shape("name", "param" to value). Names are case-insensitive.
    fun registerEffectShape(name: String, factory: KSLShapeFactory)

    // Usable in spawnCustomEntity templates as KEY:value. Keys are case-insensitive.
    fun registerEntityAttribute(key: String, handler: (LivingEntity, String) -> Unit)
}

inline fun <reified T : Any> KSLAPI.service(key: String): T? = getService(key, T::class.java)

inline fun <reified T : Any> KSLAPI.requireService(key: String): T =
    service(key) ?: error("Сервис '$key' типа ${T::class.java.name} не найден — проверь, что аддон-владелец загружен")

inline fun <reified T : KSLHook> KSLAPI.hook(hookId: String): T? = getHook(hookId, T::class.java)
