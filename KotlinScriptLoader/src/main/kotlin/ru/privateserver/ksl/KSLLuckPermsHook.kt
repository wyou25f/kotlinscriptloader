package ru.privateserver.ksl

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

class KSLLuckPermsHook {

    private val api: LuckPerms = LuckPermsProvider.get()

    fun primaryGroup(player: Player): String? =
        KSLErrors.hookSafe("LuckPerms", null) { api.userManager.getUser(player.uniqueId)?.primaryGroup }

    fun prefix(player: Player): String? =
        KSLErrors.hookSafe("LuckPerms", null) { api.userManager.getUser(player.uniqueId)?.cachedData?.metaData?.prefix }

    fun suffix(player: Player): String? =
        KSLErrors.hookSafe("LuckPerms", null) { api.userManager.getUser(player.uniqueId)?.cachedData?.metaData?.suffix }

    fun isInGroup(player: Player, group: String): Boolean =
        KSLErrors.hookSafe("LuckPerms", false) { primaryGroup(player)?.equals(group, ignoreCase = true) ?: false }
}
