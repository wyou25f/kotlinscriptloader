package ru.example

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon
import ru.privateserver.ksl.KSLAddonConfig

class ExampleAddonPlugin : JavaPlugin(), KSLAddon {

    override val addonId = "example-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Пример аддона с монетами, конфигом и собственным пакетом расширений"
    override val minApiVersion = 8

    private val coinService = CoinService()
    private lateinit var addonConfig: KSLAddonConfig

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader не найден!")
            isEnabled = false
            return
        }
        addonConfig = KSL.api.createConfig(this)
        KSL.api.registerAddon(this)
    }

    override fun onDisable() {
        if (KSL.isAvailable) {
            KSL.api.unregisterAddon(addonId)
        }
    }

    override fun onLoad(api: KSLAPI) {
        if (addonConfig.getBoolean("settings.enabled", true)) {
            val prefix = addonConfig.getString("settings.prefix", "&6[ExampleAddon]")
            logger.info("ExampleAddon config loaded: prefix=$prefix")
        } else {
            logger.info("ExampleAddon is disabled in config.yml")
        }

        api.registerService("coins", coinService)
        api.registerContextExtension(CoinContextExtension(coinService))

        // CoinService is imported by its class name; format extensions by package.
        api.addDefaultImports(
            "ru.example.CoinService",
            "ru.example.format.*"
        )
    }

    override fun onUnload() {
    }
}
