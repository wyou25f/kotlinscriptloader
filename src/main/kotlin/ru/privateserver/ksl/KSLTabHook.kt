package ru.privateserver.ksl

import org.bukkit.entity.Player

/**
 * Optional bridge to NEZNAMY's TAB plugin. API classes are discovered at runtime,
 * so servers without TAB do not acquire a hard dependency.
 */
class KSLTabHook : KSLHook {
    override val hookId = "tab"
    override val pluginName = "TAB"

    private val api: Any? by lazy {
        KSLHookReflection.loadApi("TAB", "me.neznamy.tab.api.TabAPI")
    }

    val isAvailable: Boolean get() = api != null

    private fun tabPlayer(player: Player): Any? =
        KSLHookReflection.call(api, "getPlayer", player.uniqueId)
            ?: KSLHookReflection.call(api, "getPlayer", player.name)

    private fun manager(vararg names: String): Any? =
        KSLHookReflection.callFirst(api, names.toList())

    private fun value(manager: Any?, getters: List<String>, player: Any?): String? =
        KSLHookReflection.callFirst(manager, getters, player) as? String

    private fun set(manager: Any?, method: String, player: Player, value: String?): Boolean =
        KSLErrors.hookSafe("TAB", false) {
            val tabPlayer = tabPlayer(player) ?: return@hookSafe false
            KSLHookReflection.invokeIfPresent(manager, method, tabPlayer, value)
        }

    fun nameTagPrefix(player: Player, original: Boolean = false): String? =
        KSLErrors.hookSafe("TAB", null) {
            val tabPlayer = tabPlayer(player) ?: return@hookSafe null
            value(manager("getNameTagManager"),
                if (original) listOf("getOriginalReplacedPrefix", "getOriginalRawPrefix") else listOf("getCustomPrefix"),
                tabPlayer)
        }

    fun nameTagSuffix(player: Player, original: Boolean = false): String? =
        KSLErrors.hookSafe("TAB", null) {
            val tabPlayer = tabPlayer(player) ?: return@hookSafe null
            value(manager("getNameTagManager"),
                if (original) listOf("getOriginalReplacedSuffix", "getOriginalRawSuffix") else listOf("getCustomSuffix"),
                tabPlayer)
        }

    fun tabPrefix(player: Player, original: Boolean = false): String? =
        KSLErrors.hookSafe("TAB", null) {
            val tabPlayer = tabPlayer(player) ?: return@hookSafe null
            value(manager("getTabListFormatManager", "getTablistFormatManager"),
                if (original) listOf("getOriginalPrefix", "getOriginalRawPrefix") else listOf("getCustomPrefix"),
                tabPlayer)
        }

    fun tabName(player: Player, original: Boolean = false): String? =
        KSLErrors.hookSafe("TAB", null) {
            val tabPlayer = tabPlayer(player) ?: return@hookSafe null
            value(manager("getTabListFormatManager", "getTablistFormatManager"),
                if (original) listOf("getOriginalName", "getOriginalRawName") else listOf("getCustomName"),
                tabPlayer)
        }

    fun tabSuffix(player: Player, original: Boolean = false): String? =
        KSLErrors.hookSafe("TAB", null) {
            val tabPlayer = tabPlayer(player) ?: return@hookSafe null
            value(manager("getTabListFormatManager", "getTablistFormatManager"),
                if (original) listOf("getOriginalSuffix", "getOriginalRawSuffix") else listOf("getCustomSuffix"),
                tabPlayer)
        }

    fun setNameTagPrefix(player: Player, prefix: String?): Boolean =
        set(manager("getNameTagManager"), "setPrefix", player, prefix)

    fun setNameTagSuffix(player: Player, suffix: String?): Boolean =
        set(manager("getNameTagManager"), "setSuffix", player, suffix)

    fun setTabPrefix(player: Player, prefix: String?): Boolean =
        set(manager("getTabListFormatManager", "getTablistFormatManager"), "setPrefix", player, prefix)

    fun setTabName(player: Player, name: String?): Boolean =
        set(manager("getTabListFormatManager", "getTablistFormatManager"), "setName", player, name)

    fun setTabSuffix(player: Player, suffix: String?): Boolean =
        set(manager("getTabListFormatManager", "getTablistFormatManager"), "setSuffix", player, suffix)
}
