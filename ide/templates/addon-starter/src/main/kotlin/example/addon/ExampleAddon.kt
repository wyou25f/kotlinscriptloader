package example.addon

import org.bukkit.plugin.java.JavaPlugin
import ru.privateserver.ksl.KSL
import ru.privateserver.ksl.KSLAPI
import ru.privateserver.ksl.KSLAddon
import ru.privateserver.ksl.api.KSLAddonScope
import ru.privateserver.ksl.api.KSLLogLevel
import ru.privateserver.ksl.api.KSLLogger
import ru.privateserver.ksl.api.KSLLoggerConfig
import java.time.Duration

class ExampleAddon : JavaPlugin(), KSLAddon {
    override val addonId = "example-addon"
    override val addonVersion = "1.0.0"
    override val addonDescription = "Example KSL addon starter"

    private var scope: KSLAddonScope? = null
    private var addonLog: KSLLogger? = null

    override fun onEnable() {
        if (!KSL.isAvailable) {
            logger.severe("KotlinScriptLoader is not available")
            server.pluginManager.disablePlugin(this)
            return
        }
        KSL.api.registerAddon(this)
    }

    override fun onLoad(api: KSLAPI) {
        val log = api.createLogger(
            ownerId = addonId,
            name = "lifecycle",
            config = KSLLoggerConfig().apply {
                level = KSLLogLevel.DEBUG
                prefix = "EXAMPLE"
            }
        )
        addonLog = log
        log.info("Addon loaded", "version" to addonVersion)

        // This starter registers and owns its own scheduler engine instance.
        scope = KSLAddonScope.create(api, schedulerId = "example-addon.metrics") { environment ->
            ExampleScheduler(environment)
        }

        api.addDefaultImports("example.addon.dsl.*")
        api.registerService("example-addon.greetings", ExampleGreetingService())

        scope?.repeat(
            interval = Duration.ofMinutes(1),
            initialDelay = Duration.ofSeconds(10)
        ) { task ->
            // Worker thread: keep Bukkit world/player access out of this callback.
            log.child("scheduler").debug("Heartbeat", "execution" to task.execution)
        }
    }

    override fun onUnload() {
        addonLog?.info("Addon unloading")
        scope?.close()
        scope = null
        addonLog = null
    }

    override fun onDisable() {
        if (KSL.isAvailable) {
            KSL.api.unregisterAddon(addonId)
        }
    }
}

class ExampleGreetingService {
    fun greeting(name: String): String = "Welcome, $name!"
}
