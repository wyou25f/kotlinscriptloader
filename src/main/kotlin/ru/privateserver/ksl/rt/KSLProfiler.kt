package ru.privateserver.ksl.rt

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

data class KSLProfileSnapshot(
    val name: String,
    val calls: Long,
    val failures: Long,
    val totalNanos: Long,
    val maxNanos: Long
) {
    val averageNanos: Long get() = if (calls == 0L) 0L else totalNanos / calls
    val successfulCalls: Long get() = calls - failures
    val totalMillis: Double get() = totalNanos / 1_000_000.0
    val averageMillis: Double get() = averageNanos / 1_000_000.0
    val maxMillis: Double get() = maxNanos / 1_000_000.0
}

/** Aggregates elapsed wall-clock timings. measure() records and rethrows failures. */
class KSLProfiler(val name: String) {
    private val calls = LongAdder()
    private val failures = LongAdder()
    private val totalNanos = LongAdder()
    private val maxNanos = AtomicLong()

    init {
        require(name.isNotBlank()) { "Profiler name must not be blank" }
    }

    fun record(elapsedNanos: Long, failed: Boolean = false) {
        require(elapsedNanos >= 0L) { "elapsedNanos must not be negative" }
        calls.increment()
        totalNanos.add(elapsedNanos)
        if (failed) failures.increment()
        maxNanos.accumulateAndGet(elapsedNanos) { current, measured -> maxOf(current, measured) }
    }

    inline fun <T> measure(block: () -> T): T {
        val started = System.nanoTime()
        var failed = false
        try {
            return block()
        } catch (failure: Throwable) {
            failed = true
            throw failure
        } finally {
            record(System.nanoTime() - started, failed)
        }
    }

    /** Alias that reads naturally at call sites. */
    inline fun <T> time(block: () -> T): T = measure(block)

    fun snapshot() = KSLProfileSnapshot(
        name,
        calls.sum(),
        failures.sum(),
        totalNanos.sum(),
        maxNanos.get()
    )

    /** Alias for [snapshot]. */
    fun stats(): KSLProfileSnapshot = snapshot()

    fun reset() {
        calls.reset()
        failures.reset()
        totalNanos.reset()
        maxNanos.set(0L)
    }

    /** Alias for [reset]. */
    fun clear() = reset()
}
