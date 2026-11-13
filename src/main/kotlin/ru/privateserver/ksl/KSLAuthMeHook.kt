package ru.privateserver.ksl

import org.bukkit.entity.Player
import java.time.Instant

/**
 * Read-only optional bridge to AuthMe Reloaded. It exposes authentication status only;
 * passwords and automatic login operations are intentionally not part of this hook.
 */
class KSLAuthMeHook : KSLHook {
    override val hookId = "authme"
    override val pluginName = "AuthMe"

    private val api: Any? by lazy {
        KSLHookReflection.loadApi("AuthMe", "fr.xephi.authme.api.v3.AuthMeApi")
    }

    val isAvailable: Boolean get() = api != null

    fun isAuthenticated(player: Player): Boolean =
        KSLErrors.hookSafe("AuthMe", false) {
            (KSLHookReflection.call(api, "isAuthenticated", player) as? Boolean)
                ?: (KSLHookReflection.call(api, "isAuthenticated", player.name) as? Boolean)
                ?: false
        }

    fun isRegistered(playerName: String): Boolean =
        KSLErrors.hookSafe("AuthMe", false) {
            KSLHookReflection.call(api, "isRegistered", playerName) as? Boolean ?: false
        }

    fun isNpc(player: Player): Boolean =
        KSLErrors.hookSafe("AuthMe", false) {
            KSLHookReflection.call(api, "isNpc", player) as? Boolean ?: false
        }

    fun lastLoginTime(playerName: String): Instant? =
        KSLErrors.hookSafe("AuthMe", null) {
            KSLHookReflection.call(api, "getLastLoginTime", playerName) as? Instant
        }

    fun version(): String? =
        KSLErrors.hookSafe("AuthMe", null) {
            KSLHookReflection.call(api, "getPluginVersion") as? String
        }
}
