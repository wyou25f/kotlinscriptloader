package ru.privateserver.ksl

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class KSLAddonManager(private val plugin: KotlinScriptLoaderPlugin) : KSLAPI {

    override val kslPlugin get() = plugin

    private val addons = ConcurrentHashMap<String, KSLAddon>()
    private val services = ConcurrentHashMap<String, Any>()
    private val serviceOwner = ConcurrentHashMap<String, String>()
    private val contextExtensions = ConcurrentHashMap<String, KSLContextExtension>()
    private val extensionOwner = ConcurrentHashMap<String, String>()
    private val extraImports = CopyOnWriteArrayList<String>()
    private val importOwners = ConcurrentHashMap<String, MutableSet<String>>()
    private val readyCallbacks = ConcurrentHashMap<String, MutableList<(KSLAddon) -> Unit>>()

    private val currentlyLoading = ThreadLocal<String?>()

    override fun registerAddon(addon: KSLAddon) {
        addons[addon.addonId] = addon
        val before = services.keys.toSet()

        plugin.logger.info("§9[KSL] §b▸ §fЗагрузка аддона §e${addon.addonId} §7v${addon.addonVersion}...")

        val missingDeps = addon.dependsOn.filter { !addons.containsKey(it) }
        if (missingDeps.isNotEmpty()) {
            plugin.logger.warning(
                "§9[KSL]   §e⚠ '${addon.addonId}' объявил зависимость от [${missingDeps.joinToString(", ")}], " +
                    "но ${if (missingDeps.size == 1) "он ещё не загружен" else "они ещё не загружены"}. " +
                    "Если это критично — используй api.onAddonReady(id) { } внутри onLoad для отложенной привязки, " +
                    "либо укажи depend в plugin.yml, чтобы Bukkit гарантировал порядок загрузки плагинов."
            )
        }

        currentlyLoading.set(addon.addonId)
        val result = runCatching { addon.onLoad(this) }
        currentlyLoading.set(null)

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

        readyCallbacks.remove(addon.addonId)?.forEach { callback ->
            runCatching { callback(addon) }
                .onFailure { plugin.logger.warning("§9[KSL] onAddonReady('${addon.addonId}') callback упал: ${it.message}") }
        }
    }

    override fun unregisterAddon(addonId: String) {
        addons.remove(addonId)?.let { addon ->
            runCatching { addon.onUnload() }
                .onFailure { plugin.logger.warning("§9[KSL] §e${addonId}: ошибка выгрузки: ${it.message}") }

            val orphanedServices = serviceOwner.filterValues { it == addonId }.keys
            orphanedServices.forEach { services.remove(it); serviceOwner.remove(it) }

            val orphanedExtensions = extensionOwner.filterValues { it == addonId }.keys
            orphanedExtensions.forEach { contextExtensions.remove(it); extensionOwner.remove(it) }

            val releasedImports = mutableListOf<String>()
            importOwners.forEach { (import, owners) ->
                if (owners.remove(addonId) && owners.isEmpty()) releasedImports += import
            }
            releasedImports.forEach { extraImports.remove(it); importOwners.remove(it) }

            val cleanedUp = orphanedServices.size + orphanedExtensions.size + releasedImports.size
            if (cleanedUp > 0) {
                plugin.logger.info("§9[KSL] §7${addonId}: автоматически снято $cleanedUp объект(ов) (сервисы/расширения/импорты), не отписанных вручную")
            }

            plugin.logger.info("§9[KSL] §7${addonId} выгружен")
        }
    }

    override fun registerService(key: String, instance: Any) {
        services[key] = instance
        currentlyLoading.get()?.let { serviceOwner[key] = it }
    }

    override fun unregisterService(key: String) {
        services.remove(key)
        serviceOwner.remove(key)
    }

    override fun getService(key: String): Any? = services[key]

    override fun addDefaultImports(vararg packages: String) {
        val owner = currentlyLoading.get()
        packages.forEach { pkg ->
            if (pkg !in extraImports) extraImports.add(pkg)
            if (owner != null) importOwners.computeIfAbsent(pkg) { mutableSetOf() }.add(owner)
        }
    }

    override fun registerContextExtension(extension: KSLContextExtension) {
        contextExtensions[extension.extensionId] = extension
        currentlyLoading.get()?.let { extensionOwner[extension.extensionId] = it }
    }

    override fun unregisterContextExtension(extensionId: String) {
        contextExtensions.remove(extensionId)
        extensionOwner.remove(extensionId)
    }

    override fun registeredAddons(): List<KSLAddon> = addons.values.toList()

    override fun isAddonLoaded(addonId: String): Boolean = addons.containsKey(addonId)

    override fun onAddonReady(addonId: String, callback: (KSLAddon) -> Unit) {
        val existing = addons[addonId]
        if (existing != null) {
            runCatching { callback(existing) }
                .onFailure { plugin.logger.warning("§9[KSL] onAddonReady('$addonId') callback упал: ${it.message}") }
            return
        }
        readyCallbacks.computeIfAbsent(addonId) { mutableListOf() }.add(callback)
    }

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
        serviceOwner.clear()
        contextExtensions.clear()
        extensionOwner.clear()
        extraImports.clear()
        importOwners.clear()
        readyCallbacks.clear()
    }
}
