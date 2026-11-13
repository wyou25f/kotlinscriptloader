package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import ru.privateserver.ksl.api.library.KSLModulePlanner
import ru.privateserver.ksl.api.library.KSLStandardLibraryModule

class KSLAddonManager(private val plugin: KotlinScriptLoaderPlugin) : KSLAPI {

    override val kslPlugin get() = plugin
    override val apiVersion: Int get() = KSL.API_VERSION

    // The owner is whoever asked, not the awaited addon: what the callback registers must be cleaned up with the asker.
    private class ReadyHook(val owner: String?, val callback: (KSLAddon) -> Unit)

    private val addons = ConcurrentHashMap<String, KSLAddon>()
    private val loadOrder = CopyOnWriteArrayList<String>()
    private val services = ConcurrentHashMap<String, Any>()
    private val serviceOwner = ConcurrentHashMap<String, String>()
    private val contextExtensions = KSLOwnedRegistry<KSLContextExtension>()
    private val extraImports = CopyOnWriteArrayList<String>()
    private val importOwners = ConcurrentHashMap<String, MutableSet<String>>()
    private val readyCallbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<ReadyHook>>()
    private val readyAddons = ConcurrentHashMap.newKeySet<String>()
    private val readyLock = Any()
    private data class InstalledModule(
        val module: KSLStandardLibraryModule,
        val owner: String?,
        val resourceOwner: String
    )
    private val standardModules = linkedMapOf<String, InstalledModule>()
    private val moduleLock = Any()
    private val mismatchWarned = ConcurrentHashMap.newKeySet<String>()

    val hookRegistry = KSLOwnedRegistry<KSLHook>()
    val effectShapes = KSLOwnedRegistry<KSLShapeFactory>()
    val entityAttributes = KSLOwnedRegistry<(LivingEntity, String) -> Unit>()

    private val eventBus = KSLEventBus { topic, ex ->
        plugin.logger.warning("§9[KSL] ${KSLError.ADDON_CALLBACK_FAILED.tag} подписчик темы '$topic' упал: ${ex::class.simpleName}: ${ex.message}")
    }

    private val currentlyLoading = ThreadLocal<String?>()

    // Restores the previous owner instead of clearing it: addons may load other addons from onLoad.
    private inline fun <T> asOwner(owner: String?, block: () -> T): T {
        val previous = currentlyLoading.get()
        currentlyLoading.set(owner)
        try {
            return block()
        } finally {
            if (previous == null) currentlyLoading.remove() else currentlyLoading.set(previous)
        }
    }

    override fun registerAddon(addon: KSLAddon) {
        val id = addon.addonId
        if (id.isBlank()) {
            plugin.logger.severe("§9[KSL] §c✘ Аддон с пустым ID не может быть зарегистрирован")
            return
        }

        if (addon.minApiVersion > KSL.API_VERSION) {
            plugin.logger.severe(
                "§9[KSL] §c✘ '$id' ${KSLError.ADDON_API_TOO_OLD.tag} требует KSL API v${addon.minApiVersion}, установлен v${KSL.API_VERSION} — обнови KotlinScriptLoader"
            )
            return
        }

        val existingId = registeredAddonId(id)
        if (existingId != null) {
            plugin.logger.warning("§9[KSL] §e⚠ Аддон '$id' ${KSLError.ADDON_DUPLICATE.tag} уже загружен — старый экземпляр выгружается перед заменой")
            unregisterAddon(existingId)
            if (registeredAddonId(id) != null) {
                plugin.logger.warning("§9[KSL] §eАддон '$id' не заменён: сначала выгрузи его зависимые аддоны или модули")
                return
            }
        }

        synchronized(readyLock) {
            addons[id] = addon
            readyAddons.remove(id.lowercase())
        }
        loadOrder.add(id)
        val before = services.keys.toSet()

        plugin.logger.info("§9[KSL] §b▸ §fЗагрузка аддона §e$id §7v${addon.addonVersion}...")

        val missingDeps = addon.dependsOn.filter { registeredAddonId(it) == null }
        if (missingDeps.isNotEmpty()) {
            plugin.logger.warning(
                "§9[KSL]   §e⚠ '$id' ${KSLError.ADDON_DEP_MISSING.tag} объявил зависимость от [${missingDeps.joinToString(", ")}], " +
                    "но ${if (missingDeps.size == 1) "он ещё не загружен" else "они ещё не загружены"}. " +
                    "Если это критично — используй api.onAddonReady(id) { } внутри onLoad для отложенной привязки, " +
                    "либо укажи depend в plugin.yml, чтобы Bukkit гарантировал порядок загрузки плагинов."
            )
        }

        val result = asOwner(id) { runCatching { addon.onLoad(this) } }

        if (result.isFailure) {
            plugin.logger.severe("§9[KSL] §c✘ ${KSLError.ADDON_LOAD_FAILED.tag} Ошибка загрузки '$id': ${result.exceptionOrNull()?.message}")
            // A half-initialised addon would otherwise stay listed as loaded, with whatever it managed to register.
            runCatching { addon.onUnload() }
            releaseOwned(id)
            synchronized(readyLock) {
                addons.remove(id, addon)
                readyAddons.remove(id.lowercase())
            }
            loadOrder.remove(id)
            return
        }

        (services.keys - before).forEach { key ->
            plugin.logger.info("§9[KSL]   §7↳ §aсервис §f'$key' §7зарегистрирован")
        }
        plugin.logger.info("§9[KSL] §a✔ $id §7v${addon.addonVersion} §aзагружен")

        val callbacks = synchronized(readyLock) {
            readyAddons.add(id.lowercase())
            readyCallbacks.remove(id.lowercase())?.toList().orEmpty()
        }
        callbacks.forEach { runReady(addon, it) }
    }

    override fun unregisterAddon(addonId: String) {
        val registeredId = registeredAddonId(addonId) ?: return
        val addon = addons[registeredId] ?: return

        val dependentAddons = addons.values
            .filter { it.addonId != registeredId && it.dependsOn.any { dependency -> dependency.equals(registeredId, ignoreCase = true) } }
            .map { it.addonId }
        if (dependentAddons.isNotEmpty()) {
            plugin.logger.warning("§9[KSL] §e$registeredId ${KSLError.ADDON_DEP_UNLOADING.tag} не выгружен: от него зависят аддоны [${dependentAddons.joinToString(", ")}]")
            return
        }

        val ownedModuleIds = synchronized(moduleLock) {
            standardModules.values.filter { it.owner == registeredId }.map { it.module.descriptor.id }.toSet()
        }
        val dependentModules = synchronized(moduleLock) {
            standardModules.values.filter { installed ->
                installed.module.descriptor.id !in ownedModuleIds &&
                    installed.module.descriptor.dependencies.any { dependency -> ownedModuleIds.any { it.equals(dependency, ignoreCase = true) } }
            }.map { it.module.descriptor.id }
        }
        if (dependentModules.isNotEmpty()) {
            plugin.logger.warning("§9[KSL] §e$registeredId не выгружен: его модули нужны модулям [${dependentModules.joinToString(", ")}]")
            return
        }

        synchronized(readyLock) {
            if (addons[registeredId] !== addon) return
            addons.remove(registeredId, addon)
            readyAddons.remove(registeredId.lowercase())
        }
        loadOrder.remove(registeredId)

        runCatching { addon.onUnload() }
            .onFailure { plugin.logger.warning("§9[KSL] §e$addonId: ${KSLError.ADDON_UNLOAD_FAILED.tag} ошибка выгрузки: ${it.message}") }

        val cleanedUp = releaseOwned(addonId)
        if (cleanedUp > 0) {
            plugin.logger.info("§9[KSL] §7$addonId: автоматически снято $cleanedUp объект(ов) (сервисы/расширения/импорты/подписки), не отписанных вручную")
        }
        plugin.logger.info("§9[KSL] §7$addonId выгружен")
    }

    // Everything an addon registered during onLoad (or on behalf of it); returns how many things were released.
    private fun releaseOwned(owner: String): Int {
        val ownedServices = serviceOwner.filterValues { it == owner }.keys.toList()
        ownedServices.forEach { services.remove(it); serviceOwner.remove(it) }

        val ownedExtensions = contextExtensions.releaseOwner(owner)

        val ownedModules = synchronized(moduleLock) { standardModules.values.filter { it.owner == owner }.asReversed() }
        var moduleResourcesReleased = 0
        ownedModules.forEach { installed ->
            runCatching { asOwner(installed.resourceOwner) { installed.module.uninstall(this) } }
                .onFailure { plugin.logger.warning("§9[KSL] module '${installed.module.descriptor.id}' unload failed: ${it.message}") }
            moduleResourcesReleased += releaseOwned(installed.resourceOwner)
            synchronized(moduleLock) { standardModules.remove(installed.module.descriptor.id) }
        }

        val releasedImports = mutableListOf<String>()
        importOwners.forEach { (import, owners) ->
            if (owners.remove(owner) && owners.isEmpty()) releasedImports += import
        }
        releasedImports.forEach { extraImports.remove(it); importOwners.remove(it) }

        // A pending callback of an unloaded addon would fire later and keep its classloader alive.
        readyCallbacks.values.forEach { hooks -> hooks.removeIf { it.owner == owner } }
        eventBus.clearOwner(owner)

        val releasedRegistryEntries = hookRegistry.releaseOwner(owner) +
            effectShapes.releaseOwner(owner) + entityAttributes.releaseOwner(owner) +
            plugin.placeholders.releaseOwner(owner) + plugin.textPlaceholders.releaseOwner(owner)

        return ownedServices.size + ownedExtensions + releasedImports.size + releasedRegistryEntries + ownedModules.size + moduleResourcesReleased
    }

    override fun registerService(key: String, instance: Any) {
        val owner = currentlyLoading.get()
        val previousOwner = serviceOwner[key]
        if (services.containsKey(key) && previousOwner != owner) {
            plugin.logger.warning(
                "§9[KSL] §e⚠ Сервис '$key' (владелец: ${previousOwner ?: "не указан"}) ${KSLError.ADDON_OVERWRITE.tag} перезаписан вызовом ${owner ?: "вне onLoad"}"
            )
        }
        services[key] = instance
        // Stale ownership would make the previous owner's unload delete the new owner's service.
        if (owner != null) serviceOwner[key] = owner else serviceOwner.remove(key)
    }

    override fun unregisterService(key: String) {
        services.remove(key)
        serviceOwner.remove(key)
    }

    override fun getService(key: String): Any? = services[key]

    override fun <T : Any> getService(key: String, type: Class<T>): T? {
        val instance = services[key] ?: return null
        if (type.isInstance(instance)) return type.cast(instance)
        if (mismatchWarned.add("$key|${type.name}")) {
            plugin.logger.warning(
                "§9[KSL] §e⚠ Сервис '$key' ${KSLError.ADDON_TYPE_MISMATCH.tag} имеет тип ${instance.javaClass.name}, а запрошен ${type.name}. " +
                    "Если имена совпадают — класс загружен разными classloader'ами (аддон упакован вместе с зависимостью; используй compileOnly)."
            )
        }
        return null
    }

    override fun registerStandardLibraryModule(module: KSLStandardLibraryModule) {
        val owner = currentlyLoading.get()
        synchronized(moduleLock) {
            require(module.descriptor.id !in standardModules) {
                "KSL standard-library module '${module.descriptor.id}' is already registered"
            }
            val ordered = KSLModulePlanner.resolve(standardModules.values.map { it.module } + module, KSL.API_VERSION)
            val installedNow = mutableListOf<InstalledModule>()
            try {
                ordered.forEach { candidate ->
                    if (standardModules.containsKey(candidate.descriptor.id)) return@forEach
                    val resourceOwner = moduleResourceOwner(owner, candidate.descriptor.id)
                    try {
                        asOwner(resourceOwner) {
                            candidate.install(this)
                            if (candidate.descriptor.defaultImports.isNotEmpty()) {
                                addDefaultImports(*candidate.descriptor.defaultImports.toTypedArray())
                            }
                        }
                    } catch (failure: Throwable) {
                        runCatching { asOwner(resourceOwner) { candidate.uninstall(this) } }
                        releaseOwned(resourceOwner)
                        throw failure
                    }
                    val installed = InstalledModule(candidate, owner, resourceOwner)
                    standardModules[candidate.descriptor.id] = installed
                    installedNow += installed
                }
            } catch (failure: Throwable) {
                installedNow.asReversed().forEach { installed ->
                    runCatching { asOwner(installed.resourceOwner) { installed.module.uninstall(this) } }
                    releaseOwned(installed.resourceOwner)
                    standardModules.remove(installed.module.descriptor.id)
                }
                throw IllegalStateException("Failed to install KSL standard-library module '${module.descriptor.id}'", failure)
            }
        }
    }

    override fun unregisterStandardLibraryModule(moduleId: String) {
        synchronized(moduleLock) {
            val installed = standardModules[moduleId] ?: return
            val dependents = standardModules.values
                .filter { moduleId in it.module.descriptor.dependencies }
                .map { it.module.descriptor.id }
            require(dependents.isEmpty()) {
                "Cannot unregister KSL module '$moduleId'; required by ${dependents.joinToString()}"
            }
            runCatching { asOwner(installed.resourceOwner) { installed.module.uninstall(this) } }
                .onFailure { plugin.logger.warning("§9[KSL] module '$moduleId' did not fully unload: ${it.message}") }
            releaseOwned(installed.resourceOwner)
            standardModules.remove(moduleId)
        }
    }

    private fun moduleResourceOwner(addonOwner: String?, moduleId: String): String =
        "ksl-module:${addonOwner ?: "external"}:$moduleId"

    override fun standardLibraryModules(): List<KSLStandardLibraryModule> =
        synchronized(moduleLock) { standardModules.values.map { it.module } }

    override fun addDefaultImports(vararg packages: String) {
        val owner = currentlyLoading.get()
        packages.forEach { pkg ->
            if (pkg !in extraImports) extraImports.add(pkg)
            if (owner != null) importOwners.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(owner)
        }
    }

    override fun registerContextExtension(extension: KSLContextExtension) {
        val id = extension.extensionId
        val owner = currentlyLoading.get()
        val previousOwner = contextExtensions.ownerOf(id)
        if (contextExtensions.contains(id) && previousOwner != owner) {
            plugin.logger.warning(
                "§9[KSL] §e⚠ Расширение контекста '$id' (владелец: ${previousOwner ?: "не указан"}) " +
                    "${KSLError.ADDON_OVERWRITE.tag} перезаписано вызовом ${owner ?: "вне onLoad"}"
            )
        }
        contextExtensions.put(id, extension, owner)
    }

    override fun unregisterContextExtension(extensionId: String) {
        contextExtensions.remove(extensionId)
    }

    override fun registeredAddons(): List<KSLAddon> = loadOrder.mapNotNull { addons[it] }

    private fun registeredAddonId(addonId: String): String? =
        addons.keys.firstOrNull { it.equals(addonId, ignoreCase = true) }

    override fun isAddonLoaded(addonId: String): Boolean = registeredAddonId(addonId) != null

    override fun onAddonReady(addonId: String, callback: (KSLAddon) -> Unit) {
        val hook = ReadyHook(currentlyLoading.get(), callback)
        val existing = synchronized(readyLock) {
            val registeredId = registeredAddonId(addonId)
            if (registeredId != null && registeredId.lowercase() in readyAddons) {
                addons[registeredId]
            } else {
                readyCallbacks.computeIfAbsent(addonId.lowercase()) { CopyOnWriteArrayList() }.add(hook)
                null
            }
        }
        if (existing != null) runReady(existing, hook)
    }

    private fun runReady(addon: KSLAddon, hook: ReadyHook) {
        asOwner(hook.owner) {
            runCatching { hook.callback(addon) }
                .onFailure { plugin.logger.warning("§9[KSL] onAddonReady('${addon.addonId}') ${KSLError.ADDON_CALLBACK_FAILED.tag} callback упал: ${it.message}") }
        }
    }

    override fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable =
        eventBus.subscribe(currentlyLoading.get(), topic, handler)

    override fun publish(topic: String, payload: Any?): Int = eventBus.publish(topic, payload)

    override fun reloadScripts(): Pair<Int, Int> = plugin.reloadScripts()

    override fun scripts(): List<KSLScriptInfo> = plugin.scriptInfos()

    override fun reloadScript(scriptId: String): Boolean = plugin.reloadScript(scriptId)

    override fun reloadScriptsAsync(done: (Pair<Int, Int>?) -> Unit) = plugin.reloadScriptsAsync(done)

    override fun isSandboxed(scriptId: String): Boolean = plugin.isSandboxed(scriptId)

    override fun isConsoleCommandBlocked(command: String): Boolean = plugin.isConsoleCommandBlocked(command)

    // Registration is attributed to the addon currently in onLoad; calls from anywhere else stay ownerless.
    private fun <T : Any> KSLOwnedRegistry<T>.putOwned(kind: String, key: String, value: T) {
        val owner = currentlyLoading.get()
        if (contains(key) && ownerOf(key) != owner) {
            plugin.logger.warning("§9[KSL] §e⚠ $kind '$key' (владелец: ${ownerOf(key) ?: "не указан"}) ${KSLError.ADDON_OVERWRITE.tag} перезаписан вызовом ${owner ?: "вне onLoad"}")
        }
        put(key, value, owner)
    }

    override fun registerHook(hook: KSLHook) {
        hookRegistry.putOwned("Хук", hook.hookId.lowercase(), hook)
    }

    override fun unregisterHook(hookId: String) = hookRegistry.remove(hookId.lowercase())

    override fun getHook(hookId: String): KSLHook? = hookRegistry.get(hookId.lowercase())

    override fun <T : KSLHook> getHook(hookId: String, type: Class<T>): T? {
        val hook = getHook(hookId) ?: return null
        if (type.isInstance(hook)) return type.cast(hook)
        if (mismatchWarned.add("hook:$hookId|${type.name}")) {
            plugin.logger.warning(
                "§9[KSL] §e⚠ Хук '$hookId' ${KSLError.ADDON_TYPE_MISMATCH.tag} имеет тип ${hook.javaClass.name}, а запрошен ${type.name} — " +
                    "если имена совпадают, класс загружен разными classloader'ами (используй compileOnly для API провайдера)."
            )
        }
        return null
    }

    override fun hooks(): List<KSLHook> = hookRegistry.values()

    override fun isPluginEnabled(pluginName: String): Boolean = Bukkit.getPluginManager().isPluginEnabled(pluginName)

    override fun registerPlaceholder(key: String, resolver: (OfflinePlayer) -> String?) {
        plugin.placeholders.putOwned("Плейсхолдер", key.lowercase(), resolver)
    }

    override fun unregisterPlaceholder(key: String) = plugin.placeholders.remove(key.lowercase())

    override fun registerTextPlaceholder(key: String, resolver: (Player?) -> String?) {
        plugin.registerTextPlaceholder(currentlyLoading.get() ?: "addon", key, resolver)
    }

    override fun unregisterTextPlaceholder(key: String) = plugin.textPlaceholders.remove(key.lowercase())

    override fun registerEffectShape(name: String, factory: KSLShapeFactory) {
        effectShapes.putOwned("Форма эффекта", name.lowercase(), factory)
    }

    override fun registerEntityAttribute(key: String, handler: (LivingEntity, String) -> Unit) {
        entityAttributes.putOwned("Атрибут моба", key.uppercase(), handler)
    }

    // Script subscriptions share the bus; the owner tag lets unload drop them without a tracking list.
    fun subscribeFromScript(scriptName: String, topic: String, handler: (Any?) -> Unit): AutoCloseable =
        eventBus.subscribe("script:$scriptName", topic, handler)

    fun getExtraImports(): List<String> = extraImports.toList()

    /** Classloaders of currently registered addons, in addon load order. */
    fun getScriptClassLoaders(): List<ClassLoader> = loadOrder
        .mapNotNull { addons[it]?.javaClass?.classLoader }
        .filter { it !== plugin.javaClass.classLoader }
        .distinct()

    fun getServiceKeys(): Set<String> = services.keys.toSet()

    fun notifyContextCreated(context: BukkitScriptContext) {
        contextExtensions.values().forEach { ext ->
            runCatching { ext.onContextCreated(context) }
                .onFailure { plugin.logger.warning("§9[KSL] ${KSLError.ADDON_CALLBACK_FAILED.tag} ${ext.extensionId}.onContextCreated: ${it.message}") }
        }
    }

    fun notifyContextDestroyed(scriptName: String) {
        eventBus.clearOwner("script:$scriptName")
        contextExtensions.values().forEach { ext ->
            runCatching { ext.onContextDestroyed(scriptName) }
                .onFailure { plugin.logger.warning("§9[KSL] ${KSLError.ADDON_CALLBACK_FAILED.tag} ${ext.extensionId}.onContextDestroyed: ${it.message}") }
        }
    }

    fun shutdown() {
        // Dependents were loaded after their dependencies, so unload in reverse.
        loadOrder.reversed().forEach { unregisterAddon(it) }
        addons.keys.toList().forEach { unregisterAddon(it) }
        services.clear()
        serviceOwner.clear()
        contextExtensions.clear()
        extraImports.clear()
        importOwners.clear()
        readyCallbacks.clear()
        eventBus.clear()
        hookRegistry.clear()
        effectShapes.clear()
        entityAttributes.clear()
        synchronized(moduleLock) { standardModules.clear() }
    }
}
