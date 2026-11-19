@file:Suppress("unused")

package ru.privateserver.ksl.api


import java.util.concurrent.CompletableFuture

/**
 * Non-null result model for asynchronous script reload operations.
 *
 * The legacy callback API uses null to mean that another reload is already in
 * progress. This sealed result makes that state explicit for new integrations.
 */
sealed interface KSLReloadOutcome {
    data class Script(val scriptId: String, val reloaded: Boolean) : KSLReloadOutcome
    data class Batch(val reloaded: Int, val failed: Int) : KSLReloadOutcome
    data object Busy : KSLReloadOutcome
}

/**
 * Starts a non-blocking script reload and completes with an explicit outcome.
 * The future completes on the callback thread; marshal Bukkit mutations to the
 * server thread. A compilation failure is Script(reloaded = false), not an exception.
 */
fun KSLAPI.reloadScriptFuture(scriptId: String): CompletableFuture<KSLReloadOutcome> {
    require(scriptId.isNotBlank()) { "scriptId must not be blank" }
    val result = CompletableFuture<KSLReloadOutcome>()
    try {
        reloadScriptAsync(scriptId) { reloaded ->
            result.complete(
                if (reloaded == null) KSLReloadOutcome.Busy
                else KSLReloadOutcome.Script(scriptId, reloaded)
            )
        }
    } catch (failure: Throwable) {
        result.completeExceptionally(failure)
    }
    return result
}

/**
 * Starts a non-blocking reload of all scripts. Busy is returned when another
 * reload operation already owns the reload coordinator.
 */
fun KSLAPI.reloadScriptsFuture(): CompletableFuture<KSLReloadOutcome> {
    val result = CompletableFuture<KSLReloadOutcome>()
    try {
        reloadScriptsAsync { counts ->
            result.complete(
                if (counts == null) KSLReloadOutcome.Busy
                else KSLReloadOutcome.Batch(counts.first, counts.second)
            )
        }
    } catch (failure: Throwable) {
        result.completeExceptionally(failure)
    }
    return result
}
