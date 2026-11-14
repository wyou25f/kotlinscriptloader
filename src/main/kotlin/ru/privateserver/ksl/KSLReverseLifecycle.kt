package ru.privateserver.ksl

/**
 * Runs teardown callbacks in reverse registration order.
 * A failed callback is reported and does not prevent later resources from being released.
 */
internal object KSLReverseLifecycle {
    fun <T> run(
        items: Iterable<T>,
        action: (T) -> Unit,
        onFailure: (T, Throwable) -> Unit
    ) {
        items.toList().asReversed().forEach { item ->
            try {
                action(item)
            } catch (error: Throwable) {
                onFailure(item, error)
            }
        }
    }
}
