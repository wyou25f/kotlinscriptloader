package ru.privateserver.ksl

// For addons: what KSL knows about a script. loadMillis is -1 when the script has never been loaded.
data class KSLScriptInfo(val id: String, val loaded: Boolean, val loadMillis: Long, val errors: Int)

// Payload of the event bus topics ksl.script.loaded, ksl.script.failed and ksl.script.unloaded.
data class KSLScriptEvent(val scriptId: String, val millis: Long = 0, val error: String? = null)
