package ru.privateserver.ksl

interface KSLAddon {
    val addonId: String
    val addonVersion: String
    val addonDescription: String get() = ""
    val dependsOn: List<String> get() = emptyList()
    fun onLoad(api: KSLAPI)
    fun onUnload()
}
