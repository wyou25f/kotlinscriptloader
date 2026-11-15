package ru.privateserver.ksl.hooks

import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLError
import ru.privateserver.ksl.KSLHook

/**
 * Canonical IDs for KSL's built-in integrations.
 * Keep these values stable: scripts and addons may persist them in configuration.
 */
object KSLHookIds {
    const val VAULT = "vault"
    const val LUCK_PERMS = "luckperms"
    const val ESSENTIALS = "essentials"
    const val SKINS_RESTORER = "skinsrestorer"
    const val WORLD_EDIT = "worldedit"
    const val WORLD_GUARD = "worldguard"
    const val TAB = "tab"
    const val AUTH_ME = "authme"
    const val SKRIPT = "skript"
}

/** Minimal base class for new hook implementations. */
abstract class KSLPluginHook(
    final override val hookId: String,
    final override val pluginName: String
) : KSLHook {
    init {
        require(hookId.matches(Regex("[a-z][a-z0-9._-]*"))) {
            "Hook id must be a lowercase identifier: '$hookId'"
        }
        require(pluginName.isNotBlank()) { "Hook pluginName must not be blank" }
    }
}

/** Typed nullable lookup alias for addon code; complements the public API's hookOrNull(). */
inline fun <reified T : KSLHook> KSLAPI.findHook(hookId: String): T? =
    getHook(hookId, T::class.java)

/** Typed required lookup alias with a consistent error message. */
inline fun <reified T : KSLHook> KSLAPI.requireHookAlias(hookId: String): T =
    findHook<T>(hookId)
        ?: error("KSL hook '$hookId' of type ${T::class.java.name} is not registered")

/**
 * Registers an optional integration only while its provider plugin is enabled.
 * Factory failures are logged and reported as false rather than aborting addon startup.
 */
fun KSLAPI.registerHookIfEnabled(pluginName: String, factory: () -> KSLHook): Boolean {
    if (!isPluginEnabled(pluginName)) return false
    return try {
        registerHook(factory())
        true
    } catch (failure: Throwable) {
        kslPlugin.logger.warning(
            "[KSL] ${KSLError.HOOK_INIT_FAILED.tag} hook for $pluginName failed: ${failure::class.simpleName}: ${failure.message}"
        )
        false
    }
}
