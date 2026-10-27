package ru.privateserver.ksl

object KSL {

    // Bump when KSLAPI gains something addons may depend on; addons can require it via KSLAddon.minApiVersion.
    const val API_VERSION = 3

    private var _api: KSLAPI? = null

    val api: KSLAPI
        get() = _api ?: error("KotlinScriptLoader не установлен или ещё не инициализирован")

    val isAvailable: Boolean
        get() = _api != null

    internal fun init(api: KSLAPI) { _api = api }
    internal fun shutdown() { _api = null }
}
