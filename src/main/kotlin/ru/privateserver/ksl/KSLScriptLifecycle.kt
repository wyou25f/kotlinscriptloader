package ru.privateserver.ksl

/**
 * Replaces a script runtime transactionally:
 * old registrations are removed before evaluation, and any partial registrations
 * created by a failed evaluation are removed before returning.
 */
internal fun replaceScriptRuntime(
    unload: () -> Unit,
    evaluate: () -> Boolean
): Boolean {
    unload()
    return try {
        evaluate().also { succeeded ->
            if (!succeeded) unload()
        }
    } catch (error: Throwable) {
        runCatching { unload() }.onFailure(error::addSuppressed)
        throw error
    }
}
