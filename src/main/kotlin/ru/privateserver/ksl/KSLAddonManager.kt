package ru.privateserver.ksl

import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class KSLAddonManager(private val plugin: KotlinScriptLoaderPlugin) : KSLAPI {

    override val kslPlugin get() = plugin
    override val apiVersion: Int get() = KSL.API_VERSION

    // The owner is whoever asked, not the awaited addon: what the callback registers must be cleaned up with the asker.
    private class ReadyHook(val owner: String?, val callback: (KSLAddon) -> Unit)

    private val addons = ConcurrentHashMap<String, KSLAddon>()
    private val loadOrder = CopyOnWriteArrayList<String>()
    private val services = ConcurrentHashMap<String, Any>()
    private val serviceOwner = ConcurrentHashMap<String, String>()
    private val contextExtensions = ConcurrentHashMap<String, KSLContextExtension>()
    private val extensionOwner = ConcurrentHashMap<String, String>()
    private val extraImports = CopyOnWriteArrayList<String>()
    private val importOwners = ConcurrentHashMap<String, MutableSet<String>>()
    private val readyCallbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<ReadyHook>>()
    private val mismatchWarned = ConcurrentHashMap.newKeySet<String>()

    val hookRegistry = KSLOwnedRegistry<KSLHook>()
    val effectShapes = KSLOwnedRegistry<KSLShapeFactory>()
    val entityAttributes = KSLOwnedRegistry<(LivingEntity, String) -> Unit>()

    private val eventBus = KSLEventBus { topic, ex ->
        plugin.logger.warning("§9[KSL] подписчик темы '$topic' упал: ${ex::class.simpleName}: ${ex.message}")
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

        if (addon.minApiVersion > KSL.API_VERSION) {
            plugin.logger.severe(
                "§9[KSL] §c✘ '$id' требует KSL API v${addon.minApiVersion}, установлен v${KSL.API_VERSION} — обнови KotlinScriptLoader"
            )
            return
        }

        if (addons.containsKey(id)) {
            plugin.logger.warning("§9[KSL] §e⚠ Аддон '$id' уже загружен — старый экземпляр выгружается перед заменой")
            unregisterAddon(id)
        }

        addons[id] = addon
        loadOrder.add(id)
        val before = services.keys.toSet()

        plugin.logger.info("§9[KSL] §b▸ §fЗагрузка аддона §e$id §7v${addon.addonVersion}...")

        val missingDeps = addon.dependsOn.filter { !addons.containsKey(it) }
        if (missingDeps.isNotEmpty()) {
            plugin.logger.warning(
                "§9[KSL]   §e⚠ '$id' объявил зависимость от [${missingDeps.joinToString(", ")}], " +
                    "но ${if (missingDeps.size == 1) "он ещё не загружен" else "они ещё не загружены"}. " +
                    "Если это критично — используй api.onAddonReady(id) { } внутри onLoad для отложенной привязки, " +
                    "либо укажи depend в plugin.yml, чтобы Bukkit гарантировал порядок загрузки плагинов."
            )
        }

        val result = asOwner(id) { runCatching { addon.onLoad(this) } }

        if (result.isFailure) {
            plugin.logger.severe("§9[KSL] §c✘ Ошибка загрузки '$id': ${result.exceptionOrNull()?.message}")
            // A half-initialised addon would otherwise stay listed as loaded, with whatever it managed to register.
            runCatching { addon.onUnload() }
            releaseOwned(id)
            addons.remove(id)
            loadOrder.remove(id)
            return
        }

        (services.keys - before).forEach { key ->
            plugin.logger.info("§9[KSL]   §7↳ §aсервис §f'$key' §7зарегистрирован")
        }
        plugin.logger.info("§9[KSL] §a✔ $id §7v${addon.addonVersion} §aзагружен")

        readyCallbacks.remove(id)?.forEach { runReady(addon, it) }
    }

    override fun unregisterAddon(addonId: String) {
        val addon = addons.remove(addonId) ?: return
        loadOrder.remove(addonId)

        val dependents = addons.values.filter { addonId in it.dependsOn }.map { it.addonId }
        if (dependents.isNotEmpty()) {
            plugin.logger.warning("§9[KSL] §e$addonId выгружается, хотя от него зависят: [${dependents.joinToString(", ")}]")
        }

        runCatching { addon.onUnload() }
            .onFailure { plugin.logger.warning("§9[KSL] §e$addonId: ошибка выгрузки: ${it.message}") }

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

        val ownedExtensions = extensionOwner.filterValues { it == owner }.keys.toList()
        ownedExtensions.forEach { contextExtensions.remove(it); extensionOwner.remove(it) }

        val releasedImports = mutableListOf<String>()
        importOwners.forEach { (import, owners) ->
            if (owners.remove(owner) && owners.isEmpty()) releasedImports += import
        }
        releasedImports.forEach { extraImports.remove(it); importOwners.remove(it) }

        // A pending callback of an unloaded addon would fire later and keep its classloader alive.
        readyCallbacks.values.forEach { hooks -> hooks.removeIf { it.owner == owner } }
        eventBus.clearOwner(owner)

        val releasedRegistryEntries = hookRegistry.releaseOwner(owner) +
            effectShapes.releaseOwner(owner) + entityAttributes.releaseOwner(owner)

        return ownedServices.size + ownedExtensions.size + releasedImports.size + releasedRegistryEntries
    }

    override fun registerService(key: String, instance: Any) {
        val owner = currentlyLoading.get()
        val previousOwner = serviceOwner[key]
        if (services.containsKey(key) && previousOwner != owner) {
            plugin.logger.warning(
                "§9[KSL] §e⚠ Сервис '$key' (владелец: ${previousOwner ?: "не указан"}) перезаписан вызовом ${owner ?: "вне onLoad"}"
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
                "§9[KSL] §e⚠ Сервис '$key' имеет тип ${instance.javaClass.name}, а запрошен ${type.name}. " +
                    "Если имена совпадают — класс загружен разными classloader'ами (аддон упакован вместе с зависимостью; используй compileOnly)."
            )
        }
        return null
    }

    override fun addDefaultImports(vararg packages: String) {
        val owner = currentlyLoading.get()
        packages.forEach { pkg ->
            if (pkg !in extraImports) extraImports.add(pkg)
            if (owner != null) importOwners.computeIfAbsent(pkg) { ConcurrentHashMap.newKeySet() }.add(owner)
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

    override fun registeredAddons(): List<KSLAddon> = loadOrder.mapNotNull { addons[it] }

    override fun isAddonLoaded(addonId: String): Boolean = addons.containsKey(addonId)

    override fun onAddonReady(addonId: String, callback: (KSLAddon) -> Unit) {
        val hook = ReadyHook(currentlyLoading.get(), callback)
        val existing = addons[addonId]
        if (existing != null) {
            runReady(existing, hook)
            return
        }
        readyCallbacks.computeIfAbsent(addonId) { CopyOnWriteArrayList() }.add(hook)
    }

    private fun runReady(addon: KSLAddon, hook: ReadyHook) {
        asOwner(hook.owner) {
            runCatching { hook.callback(addon) }
                .onFailure { plugin.logger.warning("§9[KSL] onAddonReady('${addon.addonId}') callback упал: ${it.message}") }
        }
    }

    override fun subscribe(topic: String, handler: (Any?) -> Unit): AutoCloseable =
        eventBus.subscribe(currentlyLoading.get(), topic, handler)

    override fun publish(topic: String, payload: Any?): Int = eventBus.publish(topic, payload)

    override fun reloadScripts(): Pair<Int, Int> = plugin.reloadScripts()

    // Registration is attributed to the addon currently in onLoad; calls from anywhere else stay ownerless.
    private fun <T : Any> KSLOwnedRegistry<T>.putOwned(kind: String, key: String, value: T) {
        val owner = currentlyLoading.get()
        if (contains(key) && ownerOf(key) != owner) {
            plugin.logger.warning("§9[KSL] §e⚠ $kind '$key' (владелец: ${ownerOf(key) ?: "не указан"}) перезаписан вызовом ${owner ?: "вне onLoad"}")
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
                "§9[KSL] §e⚠ Хук '$hookId' имеет тип ${hook.javaClass.name}, а запрошен ${type.name} — " +
                    "если имена совпадают, класс загружен разными classloader'ами (используй compileOnly для API провайдера)."
            )
        }
        return null
    }

    override fun hooks(): List<KSLHook> = hookRegistry.values()

    override fun isPluginEnabled(pluginName: String): Boolean = Bukkit.getPluginManager().isPluginEnabled(pluginName)

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

    fun getServiceKeys(): Set<String> = services.keys.toSet()

    fun notifyContextCreated(context: BukkitScriptContext) {
        contextExtensions.values.forEach { ext ->
            runCatching { ext.onContextCreated(context) }
                .onFailure { plugin.logger.warning("§9[KSL] ${ext.extensionId}.onContextCreated: ${it.message}") }
        }
    }

    fun notifyContextDestroyed(scriptName: String) {
        eventBus.clearOwner("script:$scriptName")
        contextExtensions.values.forEach { ext ->
            runCatching { ext.onContextDestroyed(scriptName) }
                .onFailure { plugin.logger.warning("§9[KSL] ${ext.extensionId}.onContextDestroyed: ${it.message}") }
        }
    }

    fun shutdown() {
        // Dependents were loaded after their dependencies, so unload in reverse.
        loadOrder.reversed().forEach { unregisterAddon(it) }
        addons.keys.toList().forEach { unregisterAddon(it) }
        services.clear()
        serviceOwner.clear()
        contextExtensions.clear()
        extensionOwner.clear()
        extraImports.clear()
        importOwners.clear()
        readyCallbacks.clear()
        eventBus.clear()
        hookRegistry.clear()
        effectShapes.clear()
        entityAttributes.clear()
    }
}
