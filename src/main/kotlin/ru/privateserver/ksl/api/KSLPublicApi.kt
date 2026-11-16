@file:Suppress("unused")

package ru.privateserver.ksl.api

import ru.privateserver.ksl.BukkitScriptContext as CoreBukkitScriptContext
import ru.privateserver.ksl.KotlinScriptLoaderPlugin
import ru.privateserver.ksl.KSL as CoreKSL
import ru.privateserver.ksl.KSLAddonConfig as CoreKSLAddonConfig
import ru.privateserver.ksl.KSLAPI as CoreKSLAPI
import ru.privateserver.ksl.KSLAddon as CoreKSLAddon
import ru.privateserver.ksl.KSLContextExtension as CoreKSLContextExtension
import ru.privateserver.ksl.KSLHook as CoreKSLHook
import ru.privateserver.ksl.KSLTabHook as CoreKSLTabHook
import ru.privateserver.ksl.KSLAuthMeHook as CoreKSLAuthMeHook
import ru.privateserver.ksl.KSLSkriptHook as CoreKSLSkriptHook
import ru.privateserver.ksl.KSLSkriptDiagnostics as CoreKSLSkriptDiagnostics
import ru.privateserver.ksl.KSLScriptInfo as CoreKSLScriptInfo
import ru.privateserver.ksl.KSLScriptState as CoreKSLScriptState
import ru.privateserver.ksl.KSLShapeFactory as CoreKSLShapeFactory
import ru.privateserver.ksl.KSLSoundSpec as CoreKSLSoundSpec
import ru.privateserver.ksl.KSLSoundSequence as CoreKSLSoundSequence
import ru.privateserver.ksl.KSLSoundSequenceBuilder as CoreKSLSoundSequenceBuilder
import ru.privateserver.ksl.KSLEffectSpec as CoreKSLEffectSpec
import ru.privateserver.ksl.KSLEntityBuilder as CoreKSLEntityBuilder
import ru.privateserver.ksl.KSLEntityBehaviorBuilder as CoreKSLEntityBehaviorBuilder
import ru.privateserver.ksl.std.item.KSLCustomEnchantDamageTakenContext as CoreKSLCustomEnchantDamageTakenContext
import ru.privateserver.ksl.KSLScriptEvent as CoreKSLScriptEvent
import ru.privateserver.ksl.api.library.KSLModuleDescriptor as CoreKSLModuleDescriptor
import ru.privateserver.ksl.api.library.KSLModulePlanner as CoreKSLModulePlanner
import ru.privateserver.ksl.api.library.KSLStandardLibraryModule as CoreKSLStandardLibraryModule
import ru.privateserver.ksl.meslib2.MesLib2 as CoreMesLib2
import ru.privateserver.ksl.meslib2.MesLib2Info as CoreMesLib2Info
import ru.privateserver.ksl.rt.KSLCache as CoreKSLCache
import ru.privateserver.ksl.rt.KSLProfiler as CoreKSLProfiler
import ru.privateserver.ksl.rt.KSLWorkScheduler as CoreKSLWorkScheduler
import ru.privateserver.ksl.rt.KSLWorkPriority as CoreKSLWorkPriority
import ru.privateserver.ksl.rt.KSLWorkHandle as CoreKSLWorkHandle
import ru.privateserver.ksl.rt.KSLWorkState as CoreKSLWorkState
import ru.privateserver.ksl.rt.KSLWorkInfo as CoreKSLWorkInfo
import ru.privateserver.ksl.rt.KSLWorkResult as CoreKSLWorkResult
import ru.privateserver.ksl.rt.KSLWorkSnapshot as CoreKSLWorkSnapshot
import ru.privateserver.ksl.rt.KSLWorkFailure as CoreKSLWorkFailure
import ru.privateserver.ksl.rt.KSLWorkOwnerSnapshot as CoreKSLWorkOwnerSnapshot
import ru.privateserver.ksl.rt.KSLRuntimeSnapshot as CoreKSLRuntimeSnapshot
import ru.privateserver.ksl.rt.KSLRuntimeMonitor
import ru.privateserver.ksl.kslshu.KSLTaskContext as CoreKSLTaskContext
import ru.privateserver.ksl.kslshu.KSLTaskHandle as CoreKSLTaskHandle
import ru.privateserver.ksl.kslshu.KSLTaskRepeatMode as CoreKSLTaskRepeatMode
import ru.privateserver.ksl.kslshu.KSLTaskResult as CoreKSLTaskResult
import ru.privateserver.ksl.kslshu.KSLTaskScheduler as CoreKSLTaskScheduler
import ru.privateserver.ksl.kslshu.KSLTaskSchedulerEnvironment as CoreKSLTaskSchedulerEnvironment
import ru.privateserver.ksl.kslshu.KSLTaskSchedulerFactory as CoreKSLTaskSchedulerFactory
import ru.privateserver.ksl.kslshu.KSLTaskSchedulerRegistry as CoreKSLTaskSchedulerRegistry
import ru.privateserver.ksl.kslshu.KSLTaskScope as CoreKSLTaskScope
import ru.privateserver.ksl.kslshu.KSLTaskSpec as CoreKSLTaskSpec
import ru.privateserver.ksl.kslshu.KSLTaskState as CoreKSLTaskState
import ru.privateserver.ksl.kslshu.KSLOfficialTaskScheduler as CoreKSLOfficialTaskScheduler

/**
 * Stable public entry point for KSL integrations and script libraries.
 *
 * In a KSL script or addon, import this package with:
 * `import ru.privateserver.ksl.api.*`
 */
typealias KSLAPI = CoreKSLAPI
/** YAML configuration wrapper returned by [KSLAPI.createConfig]. */
typealias KSLAddonConfig = CoreKSLAddonConfig
/** Short Kotlin-friendly name for the public addon API. */
typealias KslApi = CoreKSLAPI
/** Short alias for integrations that prefer a minimal import name. */
typealias Api = CoreKSLAPI
/** Concise aliases for the public addon contract. */
typealias KslAddon = CoreKSLAddon
typealias KslHook = CoreKSLHook
 typealias KslScriptInfo = CoreKSLScriptInfo
/** Explicit name for integrations that prefer the public-contract terminology. */
typealias KSLPublicAPI = CoreKSLAPI
typealias KSLAddon = CoreKSLAddon
typealias KSLContextExtension = CoreKSLContextExtension
typealias KSLHook = CoreKSLHook
typealias KSLTabHook = CoreKSLTabHook
typealias KSLAuthMeHook = CoreKSLAuthMeHook
typealias KSLSkriptHook = CoreKSLSkriptHook
typealias KSLSkriptDiagnostics = CoreKSLSkriptDiagnostics
typealias KSLScriptInfo = CoreKSLScriptInfo
/** Current script-registry state, including failed reloads that preserve a previous runtime. */
typealias KSLScriptState = CoreKSLScriptState
typealias BukkitScriptContext = CoreBukkitScriptContext
typealias KSLShapeFactory = CoreKSLShapeFactory
/** Reusable sound definition exposed by the script sound DSL. */
typealias KSLSoundSpec = CoreKSLSoundSpec
/** Reusable tick-based sound timeline. */
typealias KSLSoundSequence = CoreKSLSoundSequence
/** Builder used inside soundSequence { ... }. */
typealias KSLSoundSequenceBuilder = CoreKSLSoundSequenceBuilder
/** Particle pattern builder used by playEffect/particles. */
typealias KSLEffectSpec = CoreKSLEffectSpec
/** Configuration builder for a spawned living entity. */
typealias KSLEntityBuilder = CoreKSLEntityBuilder
/** Event and tick callbacks attached to one living entity. */
typealias KSLEntityBehaviorBuilder = CoreKSLEntityBehaviorBuilder
/** Context for armor enchantments reacting to damage taken. */
typealias KSLCustomEnchantDamageTakenContext = CoreKSLCustomEnchantDamageTakenContext
typealias KSLScriptEvent = CoreKSLScriptEvent
typealias KSLModuleDescriptor = CoreKSLModuleDescriptor
typealias KSLStandardLibraryModule = CoreKSLStandardLibraryModule
typealias MesLib2 = CoreMesLib2
/** Short aliases for the script-library facade. */
typealias KSLMesLib2 = CoreMesLib2
typealias MesLib = CoreMesLib2
typealias MesLib2Info = CoreMesLib2Info
typealias KSLCache<K, V> = CoreKSLCache<K, V>
typealias KSLProfiler = CoreKSLProfiler
typealias KSLWorkScheduler = CoreKSLWorkScheduler
typealias KSLWorkPriority = CoreKSLWorkPriority
typealias KSLWorkHandle = CoreKSLWorkHandle
typealias KSLWorkState = CoreKSLWorkState
typealias KSLWorkInfo = CoreKSLWorkInfo
typealias KSLWorkResult = CoreKSLWorkResult
typealias KSLWorkSnapshot = CoreKSLWorkSnapshot
typealias KSLWorkFailure = CoreKSLWorkFailure
typealias KSLWorkOwnerSnapshot = CoreKSLWorkOwnerSnapshot
typealias KSLRuntimeSnapshot = CoreKSLRuntimeSnapshot
/** Typed metadata supplied to a scheduler task invocation. */
typealias KSLTaskContext = CoreKSLTaskContext
typealias KSLTaskHandle = CoreKSLTaskHandle
typealias KSLTaskRepeatMode = CoreKSLTaskRepeatMode
typealias KSLTaskResult = CoreKSLTaskResult
typealias KSLTaskScheduler = CoreKSLTaskScheduler
typealias KSLTaskSchedulerEnvironment = CoreKSLTaskSchedulerEnvironment
typealias KSLTaskSchedulerFactory = CoreKSLTaskSchedulerFactory
typealias KSLTaskSchedulerRegistry = CoreKSLTaskSchedulerRegistry
typealias KSLTaskScope = CoreKSLTaskScope
typealias KSLTaskSpec = CoreKSLTaskSpec
typealias KSLTaskState = CoreKSLTaskState
typealias KSLOfficialTaskScheduler = CoreKSLOfficialTaskScheduler

/** Current read-only JVM, CPU, thread and GC telemetry. */
val kslRuntimeStats: KSLRuntimeSnapshot
    get() = KSLRuntimeMonitor.snapshot()

/** Function-form alias for [kslRuntimeStats]. */
fun runtimeStats(): KSLRuntimeSnapshot = KSLRuntimeMonitor.snapshot()

/** Alternative name for integrations that expose runtime telemetry as metrics. */
val runtimeMetrics: KSLRuntimeSnapshot
    get() = KSLRuntimeMonitor.snapshot()

/** Snapshot function with an explicit KSL prefix. */
fun kslRuntimeSnapshot(): KSLRuntimeSnapshot = KSLRuntimeMonitor.snapshot()

/** Stable event-bus topic names published by KSL. */
object KSLTopics {
    const val SCRIPT_LOADED: String = "ksl.script.loaded"
    const val SCRIPT_FAILED: String = "ksl.script.failed"
    const val SCRIPT_UNLOADED: String = "ksl.script.unloaded"
}

/** Public facade for dependency planning without importing the internal subpackage. */
object KSLModulePlanner {
    @JvmStatic
    @JvmOverloads
    fun resolve(
        modules: Collection<KSLStandardLibraryModule>,
        apiVersion: Int = KSL_API_VERSION
    ): List<KSLStandardLibraryModule> = CoreKSLModulePlanner.resolve(modules, apiVersion)
}

/** API compatibility level of the installed KSL build. */
const val KSL_API_VERSION: Int = CoreKSL.API_VERSION

/** The installed KSL API. Throws a descriptive error if KSL is not initialized yet. */
val ksl: KSLAPI
    get() = CoreKSL.api

/** Additional concise aliases for the installed public API. */
val kslApi: KSLAPI
    get() = CoreKSL.api

/** Short alias for the installed API instance. */
val kslInstance: KSLAPI
    get() = CoreKSL.api

val api: KSLAPI
    get() = CoreKSL.api

/** Explicitly named alias for the installed public API. */
val kslapi: KSLAPI
    get() = CoreKSL.api

/** The installed KSL API, or null while KSL is unavailable or shutting down. */
val kslOrNull: KSLAPI?
    get() = CoreKSL.apiOrNull

/** Whether KSL has completed initialization and its API is available. */
val isKslAvailable: Boolean
    get() = CoreKSL.isAvailable

/**
 * Java-friendly access point. Kotlin typealiases are not emitted as Java classes,
 * so Java integrations should obtain the real API interface through this object.
 */
object KSLApiProvider {
    @JvmStatic
    val isAvailable: Boolean
        get() = CoreKSL.isAvailable

    @JvmStatic
    val api: KSLAPI?
        get() = CoreKSL.apiOrNull

    @JvmStatic
    fun requireApi(): KSLAPI = CoreKSL.api
}

/** Returns a typed service registered by a KSL addon, or null when it is unavailable. */
inline fun <reified T : Any> KSLAPI.service(key: String): T? =
    getService(key, T::class.java)

/** Returns a required typed service or fails with an actionable error. */
inline fun <reified T : Any> KSLAPI.requireService(key: String): T =
    service<T>(key)
        ?: error("KSL service '$key' of type ${T::class.java.name} is not registered")

/** Short alias for [requireService]. */
inline fun <reified T : Any> KSLAPI.serviceOrThrow(key: String): T =
    requireService(key)

/** Short alias for [addDefaultImports] when registering addon-owned script packages. */
fun KSLAPI.addImports(vararg packages: String) =
    addDefaultImports(*packages)

/** Returns a typed integration hook, or null when no matching hook is registered. */
inline fun <reified T : KSLHook> KSLAPI.hook(hookId: String): T? =
    getHook(hookId, T::class.java)

/** Returns a typed hook or fails with an actionable message. */
inline fun <reified T : KSLHook> KSLAPI.requireHook(hookId: String): T =
    hook<T>(hookId)
        ?: error("KSL hook '$hookId' of type ${T::class.java.name} is not registered")

/** Checks whether a hook is currently registered without requiring its implementation type. */
fun KSLAPI.hasHook(hookId: String): Boolean = getHook(hookId) != null

/** Finds a loaded addon by id, ignoring case. */
fun KSLAPI.findAddon(addonId: String): KSLAddon? =
    registeredAddons().firstOrNull { it.addonId.equals(addonId, ignoreCase = true) }

/** Returns a loaded addon or fails with an actionable message. */
fun KSLAPI.requireAddon(addonId: String): KSLAddon =
    findAddon(addonId) ?: error("KSL addon '$addonId' is not loaded")

/** Finds a known script by id, ignoring case. Unloaded scripts are included. */
fun KSLAPI.findScript(scriptId: String): KSLScriptInfo? =
    scripts().firstOrNull { it.id.equals(scriptId, ignoreCase = true) }

/** Returns script metadata or fails with an actionable message. */
fun KSLAPI.requireScript(scriptId: String): KSLScriptInfo =
    findScript(scriptId) ?: error("KSL script '$scriptId' is not known to KSL")

/** Checks whether a known script is currently loaded. */
fun KSLAPI.isScriptLoaded(scriptId: String): Boolean = findScript(scriptId)?.loaded == true

/**
 * Subscribes to a typed script lifecycle event.
 *
 * Handlers run synchronously on the thread that publishes the event. Keep Bukkit
 * world/entity/inventory access on the server thread. The returned subscription
 * should be closed when the integration no longer needs it.
 */
fun KSLAPI.onScriptLoaded(handler: (KSLScriptEvent) -> Unit): AutoCloseable =
    subscribe(KSLTopics.SCRIPT_LOADED) { (it as? KSLScriptEvent)?.let(handler) }

/** See [onScriptLoaded] for threading and subscription-lifecycle notes. */
fun KSLAPI.onScriptFailed(handler: (KSLScriptEvent) -> Unit): AutoCloseable =
    subscribe(KSLTopics.SCRIPT_FAILED) { (it as? KSLScriptEvent)?.let(handler) }

/** See [onScriptLoaded] for threading and subscription-lifecycle notes. */
fun KSLAPI.onScriptUnloaded(handler: (KSLScriptEvent) -> Unit): AutoCloseable =
    subscribe(KSLTopics.SCRIPT_UNLOADED) { (it as? KSLScriptEvent)?.let(handler) }


/**
 * Registers an addon-owned scheduler engine. IDs should be namespaced, for example
 * "example-addon:tick". Factories are invoked when a scope requests that engine.
 */
fun KSLAPI.registerTaskSchedulerFactory(id: String, factory: KSLTaskSchedulerFactory): AutoCloseable {
    val plugin = kslPlugin as? KotlinScriptLoaderPlugin
        ?: throw IllegalStateException("Custom KSL schedulers require the official KotlinScriptLoader plugin")
    return plugin.registerSchedulerFactory(id, factory)
}

/** Creates an independently owned scheduler instance from a registered engine factory. */
fun KSLAPI.createTaskScheduler(id: String): KSLTaskScheduler {
    val plugin = kslPlugin as? KotlinScriptLoaderPlugin
        ?: throw IllegalStateException("Custom KSL schedulers require the official KotlinScriptLoader plugin")
    return plugin.createTaskScheduler(id)
}

/** Returns the currently registered scheduler engine IDs. */
fun KSLAPI.availableTaskSchedulers(): Set<String> {
    val plugin = kslPlugin as? KotlinScriptLoaderPlugin
        ?: throw IllegalStateException("Custom KSL schedulers require the official KotlinScriptLoader plugin")
    return plugin.availableTaskSchedulers()
}
