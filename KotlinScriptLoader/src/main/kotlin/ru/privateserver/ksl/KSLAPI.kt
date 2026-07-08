package ru.privateserver.ksl

import org.bukkit.plugin.java.JavaPlugin

interface KSLAPI {
    val kslPlugin: JavaPlugin
    fun registerAddon(addon: KSLAddon)
    fun unregisterAddon(addonId: String)
    fun registerService(key: String, instance: Any)
    fun unregisterService(key: String)
    fun getService(key: String): Any?
    fun addDefaultImports(vararg packages: String)
    fun registerContextExtension(extension: KSLContextExtension)
    fun unregisterContextExtension(extensionId: String)
    fun registeredAddons(): List<KSLAddon>
}
