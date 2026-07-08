package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class KSLAddonManager(private val plugin: KotlinScriptLoaderPlugin) : KSLAPI {

    override val kslPlugin get() = plugin

    private val addons = ConcurrentHashMap<String, KSLAddon>()
    private val services = ConcurrentHashMap<String, Any>()
    private val contextExtensions = ConcurrentHashMap<String, KSLContextExtension>()
    private val extraImports = CopyOnWriteArrayList<String>()

    override fun registerAddon(addon: KSLAddon) {
        addons[addon.addonId] = addon
        val before = services.keys.toSet()

        plugin.logger.info("§9[KSL] §b▸ §fЗагрузка аддона §e${addon.addonId} §7v${addon.addonVersion}...")

        val result = runCatching { addon.onLoad(this) }
        result.onFailure {
            plugin.logger.severe("§9[KSL] §c✘ Ошибка загрузки '${addon.addonId}': ${it.message}")
        }

        val added = services.keys - before
        added.forEach { key ->
            plugin.logger.info("§9[KSL]   §7↳ §aсервис §f'$key' §7зарегистрирован")
        }

        if (result.isSuccess) {
            plugin.logger.info("§9[KSL] §a✔ ${addon.addonId} §7v${addon.addonVersion} §aзагружен")
        }
    }

    override fun unregisterAddon(addonId: String) {
        addons.remove(addonId)?.let { addon ->
            runCatching { addon.onUnload() }
                .onFailure { plugin.logger.warning("§9[KSL] §e${addonId}: ошибка выгрузки: ${it.message}") }
            plugin.logger.info("§9[KSL] §7${addonId} выгружен")
        }
    }

    override fun registerService(key: String, instance: Any) {
        services[key] = instance
    }

    override fun unregisterService(key: String) {
        services.remove(key)
    }

    override fun getService(key: String): Any? = services[key]

    override fun addDefaultImports(vararg packages: String) {
        extraImports.addAll(packages)
    }

    override fun registerContextExtension(extension: KSLContextExtension) {
        contextExtensions[extension.extensionId] = extension
    }

    override fun unregisterContextExtension(extensionId: String) {
        contextExtensions.remove(extensionId)
    }

    override fun registeredAddons(): List<KSLAddon> = addons.values.toList()

    fun getExtraImports(): List<String> = extraImports.toList()

    fun getServiceKeys(): Set<String> = services.keys.toSet()

    fun notifyContextCreated(context: BukkitScriptContext) {
        contextExtensions.values.forEach { ext ->
            runCatching { ext.onContextCreated(context) }
                .onFailure { plugin.logger.warning("§9[KSL] ${ext.extensionId}.onContextCreated: ${it.message}") }
        }
    }

    fun notifyContextDestroyed(scriptName: String) {
        contextExtensions.values.forEach { ext ->
            runCatching { ext.onContextDestroyed(scriptName) }
                .onFailure { plugin.logger.warning("§9[KSL] ${ext.extensionId}.onContextDestroyed: ${it.message}") }
        }
    }

    fun shutdown() {
        addons.keys.toList().forEach { unregisterAddon(it) }
        services.clear()
        contextExtensions.clear()
        extraImports.clear()
    }
}
