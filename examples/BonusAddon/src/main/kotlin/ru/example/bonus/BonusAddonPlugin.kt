package ru.example.bonus

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.example.CoinService
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class BonusAddonPlugin : JavaPlugin(), KSLAddon, Listener {

    override val addonId = "bonus-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Бонусные монеты за вход, при наличии example-addon"
    override val dependsOn = listOf("example-addon")

    private var coins: CoinService? = null

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader не найден!")
            isEnabled = false
            return
        }
        KSL.api.registerAddon(this)
        server.pluginManager.registerEvents(this, this)
    }

    override fun onDisable() {
        if (KSL.isAvailable) {
            KSL.api.unregisterAddon(addonId)
        }
    }

    override fun onLoad(api: KSLAPI) {
        api.onAddonReady("example-addon") { addon ->
            coins = api.getService("coins") as? CoinService
            logger.info("example-addon обнаружен, бонусные монеты включены (был ли он загружен раньше или позже BonusAddon — не важно)")
        }
    }

    override fun onUnload() {
        coins = null
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player: Player = event.player
        coins?.give(player, 10L)
    }
}
