package ru.privateserver.ksl

interface KSLAddon {
    val addonId: String
    val addonVersion: String
    val addonDescription: String get() = ""
    fun onLoad(api: KSLAPI)
    fun onUnload()
}
