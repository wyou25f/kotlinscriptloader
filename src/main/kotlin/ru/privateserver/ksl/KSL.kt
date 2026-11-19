package ru.privateserver.ksl

object KSL {

    // Bump when the public addon contract gains new types or methods.
    const val API_VERSION = 19

    @Volatile
    private var _api: KSLAPI? = null

    val api: KSLAPI
        get() = _api ?: error("KotlinScriptLoader не установлен или ещё не инициализирован")

    /** Non-throwing snapshot for integrations that may initialize before KSL. */
    val apiOrNull: KSLAPI?
        get() = _api

    val isAvailable: Boolean
        get() = _api != null

    internal fun init(api: KSLAPI) { _api = api }
    internal fun shutdown() { _api = null }
}
