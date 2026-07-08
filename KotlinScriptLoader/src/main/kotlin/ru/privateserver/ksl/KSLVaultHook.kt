package ru.privateserver.ksl

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.permission.Permission
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

class KSLVaultHook {

    private val economy: Economy? = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider
    private val permission: Permission? = Bukkit.getServicesManager().getRegistration(Permission::class.java)?.provider

    val hasEconomy get() = economy != null
    val hasPermissions get() = permission != null

    fun balance(player: OfflinePlayer): Double? = economy?.getBalance(player)

    fun deposit(player: OfflinePlayer, amount: Double): Boolean =
        economy?.depositPlayer(player, amount)?.transactionSuccess() ?: false

    fun withdraw(player: OfflinePlayer, amount: Double): Boolean =
        economy?.withdrawPlayer(player, amount)?.transactionSuccess() ?: false

    fun has(player: OfflinePlayer, amount: Double): Boolean =
        economy?.has(player, amount) ?: false

    fun format(amount: Double): String = economy?.format(amount) ?: amount.toString()

    fun hasPermission(player: Player, node: String): Boolean =
        permission?.playerHas(player, node) ?: player.hasPermission(node)

    fun playerGroup(player: Player): String? = permission?.getPrimaryGroup(player)

    fun playerInGroup(player: Player, group: String): Boolean =
        permission?.playerInGroup(player, group) ?: false
}
