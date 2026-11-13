@file:Suppress("unused")

package ru.privateserver.ksl.api

import ru.privateserver.ksl.BukkitScriptContext as CoreBukkitScriptContext
import ru.privateserver.ksl.KSL as CoreKSL
import ru.privateserver.ksl.KSLAPI as CoreKSLAPI
import ru.privateserver.ksl.KSLAddon as CoreKSLAddon
import ru.privateserver.ksl.KSLContextExtension as CoreKSLContextExtension
import ru.privateserver.ksl.KSLHook as CoreKSLHook
import ru.privateserver.ksl.KSLScriptInfo as CoreKSLScriptInfo
import ru.privateserver.ksl.KSLShapeFactory as CoreKSLShapeFactory
import ru.privateserver.ksl.KSLScriptEvent as CoreKSLScriptEvent
import ru.privateserver.ksl.api.library.KSLModuleDescriptor as CoreKSLModuleDescriptor
import ru.privateserver.ksl.api.library.KSLModulePlanner as CoreKSLModulePlanner
import ru.privateserver.ksl.api.library.KSLStandardLibraryModule as CoreKSLStandardLibraryModule

/**
 * Stable public entry point for KSL integrations and script libraries.
 *
 * In a KSL script or addon, import this package with:
 * `import ru.privateserver.ksl.api.*`
 */
typealias KSLAPI = CoreKSLAPI
typealias KSLAddon = CoreKSLAddon
typealias KSLContextExtension = CoreKSLContextExtension
typealias KSLHook = CoreKSLHook
typealias KSLScriptInfo = CoreKSLScriptInfo
typealias BukkitScriptContext = CoreBukkitScriptContext
typealias KSLShapeFactory = CoreKSLShapeFactory
typealias KSLScriptEvent = CoreKSLScriptEvent
typealias KSLModuleDescriptor = CoreKSLModuleDescriptor
typealias KSLStandardLibraryModule = CoreKSLStandardLibraryModule

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

/** Returns a typed integration hook, or null when no matching hook is registered. */
inline fun <reified T : KSLHook> KSLAPI.hook(hookId: String): T? =
    getHook(hookId, T::class.java)

/** Returns a typed hook or fails with an actionable message. */
inline fun <reified T : KSLHook> KSLAPI.requireHook(hookId: String): T =
    hook<T>(hookId)
        ?: error("KSL hook '$hookId' of type ${T::class.java.name} is not registered")

/** Checks whether a hook is currently registered without requiring its implementation type. */
fun KSLAPI.hasHook(hookId: String): Boolean = getHook(hookId) != null

/** Returns a loaded addon by id, or fails with an actionable message. */
fun KSLAPI.requireAddon(addonId: String): KSLAddon =
    registeredAddons().firstOrNull { it.addonId.equals(addonId, ignoreCase = true) }
        ?: error("KSL addon '$addonId' is not loaded")
