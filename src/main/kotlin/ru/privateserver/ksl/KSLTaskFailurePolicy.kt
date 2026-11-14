package ru.privateserver.ksl

import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts consecutive callback failures. A successful execution resets the streak;
 * callers can stop a repeating task when the configured threshold is reached.
 */
internal class KSLTaskFailurePolicy(private val maxConsecutiveFailures: Int) {
    private val failures = AtomicInteger()

    init {
        require(maxConsecutiveFailures > 0) { "maxConsecutiveFailures must be positive" }
    }

    fun onSuccess() {
        failures.set(0)
    }

    fun onFailure(): Boolean = failures.incrementAndGet() >= maxConsecutiveFailures
}
