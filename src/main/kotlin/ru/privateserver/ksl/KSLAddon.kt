package ru.privateserver.ksl

interface KSLAddon {
    val addonId: String
    val addonVersion: String
    val addonDescription: String get() = ""
    val dependsOn: List<String> get() = emptyList()

    // Lowest KSL.API_VERSION this addon can work with; the addon is not loaded on an older KSL.
    val minApiVersion: Int get() = 1

    fun onLoad(api: KSLAPI)
    fun onUnload()
}
