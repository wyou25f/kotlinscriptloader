package ru.privateserver.ksl

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import org.bukkit.entity.Player

class KSLLuckPermsHook {

    private val api: LuckPerms = LuckPermsProvider.get()

    fun primaryGroup(player: Player): String? =
        api.userManager.getUser(player.uniqueId)?.primaryGroup

    fun prefix(player: Player): String? =
        api.userManager.getUser(player.uniqueId)?.cachedData?.metaData?.prefix

    fun suffix(player: Player): String? =
        api.userManager.getUser(player.uniqueId)?.cachedData?.metaData?.suffix

    // Сравнение именно с primary group, а не полная проверка наследования —
    // для большинства скриптов "в какой я группе" означает именно это,
    // а полноценный inheritance-чек через QueryOptions заметно тяжелее API.
    fun isInGroup(player: Player, group: String): Boolean =
        primaryGroup(player)?.equals(group, ignoreCase = true) ?: false
}
