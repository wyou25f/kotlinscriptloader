package ru.example

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon

class ExampleAddonPlugin : JavaPlugin(), KSLAddon {

    override val addonId = "example-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Пример аддона с монетами"

    private val coinService = CoinService()

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader не найден!")
            isEnabled = false
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onDisable() {
        if (KSL.isAvailable) {
            KSL.api.unregisterAddon(addonId)
        }
    }

    override fun onLoad(api: KSLAPI) {
        api.registerService("coins", coinService)
        api.registerContextExtension(CoinContextExtension(coinService))
        api.addDefaultImports("ru.example.CoinService")
    }

    override fun onUnload() {
    }
}
