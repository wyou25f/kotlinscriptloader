package ru.privateserver.ksl

interface KSLContextExtension {
    val extensionId: String
    fun onContextCreated(context: BukkitScriptContext)
    fun onContextDestroyed(scriptName: String)
}
