package ru.privateserver.ksl.hooks

import ru.privateserver.ksl.*
import com.earth2me.essentials.Essentials
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.math.BigDecimal

open class KSLEssentialsHook : KSLHook {

    override val hookId = KSLHookIds.ESSENTIALS
    override val pluginName = "Essentials"

    private val essentials: Essentials
        get() = Bukkit.getPluginManager().getPlugin(pluginName) as? Essentials
            ?: error("EssentialsX is not currently available")

    fun balance(player: Player): Double? =
        KSLErrors.hookSafe("EssentialsX", null) { essentials.getUser(player)?.money?.toDouble() }

    fun setBalance(player: Player, amount: Double) {
        KSLErrors.hookSafe("EssentialsX", Unit) { essentials.getUser(player)?.setMoney(BigDecimal.valueOf(amount)) }
    }

    fun isAfk(player: Player): Boolean =
        KSLErrors.hookSafe("EssentialsX", false) { essentials.getUser(player)?.isAfk ?: false }
}
