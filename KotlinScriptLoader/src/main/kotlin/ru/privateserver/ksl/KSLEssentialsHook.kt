package ru.privateserver.ksl

import com.earth2me.essentials.Essentials
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.math.BigDecimal

class KSLEssentialsHook {

    private val essentials: Essentials =
        Bukkit.getPluginManager().getPlugin("Essentials") as Essentials

    fun balance(player: Player): Double? =
        KSLErrors.hookSafe("EssentialsX", null) { essentials.getUser(player)?.money?.toDouble() }

    fun setBalance(player: Player, amount: Double) {
        KSLErrors.hookSafe("EssentialsX", Unit) { essentials.getUser(player)?.setMoney(BigDecimal.valueOf(amount)) }
    }

    fun isAfk(player: Player): Boolean =
        KSLErrors.hookSafe("EssentialsX", false) { essentials.getUser(player)?.isAfk ?: false }
}
