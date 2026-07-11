package ru.example

import ru.privateserver.ksl.BukkitScriptContext
import ru.privateserver.ksl.KSLContextExtension

class CoinContextExtension(private val coinService: CoinService) : KSLContextExtension {

    override val extensionId = "example-coins"

    override fun onContextCreated(context: BukkitScriptContext) {
    }

    override fun onContextDestroyed(scriptName: String) {
    }
}
