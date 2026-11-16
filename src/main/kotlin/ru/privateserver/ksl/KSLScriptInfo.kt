package ru.privateserver.ksl

/** Runtime state exposed by the script registry. */
enum class KSLScriptState {
    /** The latest attempted version is active. */
    LOADED,
    /** The previous version is still active, but the latest reload attempt failed. */
    DEGRADED,
    /** No version is active and the latest load attempt failed. */
    FAILED,
    /** The script exists but has no active runtime. */
    UNLOADED
}

// For addons: what KSL knows about a script. loadMillis is -1 when no version is currently loaded.
data class KSLScriptInfo(val id: String, val loaded: Boolean, val loadMillis: Long, val errors: Int) {
    // Additional snapshot fields live outside the primary constructor to preserve the existing
    // four-argument JVM constructor and data-class component/copy signatures.
    var state: KSLScriptState = if (loaded) KSLScriptState.LOADED
        else if (errors > 0) KSLScriptState.FAILED else KSLScriptState.UNLOADED
        internal set

    /** Last compile/evaluation failure, if the most recent attempt failed. */
    var lastError: String? = null
        internal set

    /** Unix epoch milliseconds of the most recent load attempt, or -1 if never attempted. */
    var lastAttemptAtMillis: Long = -1L
        internal set

    /** False when the runtime is still active after its source file was removed. */
    var sourceExists: Boolean = true
        internal set
}

// Payload of the event bus topics ksl.script.loaded, ksl.script.failed and ksl.script.unloaded.
data class KSLScriptEvent(val scriptId: String, val millis: Long = 0, val error: String? = null)
