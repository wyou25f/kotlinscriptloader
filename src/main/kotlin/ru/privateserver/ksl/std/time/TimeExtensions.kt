package ru.privateserver.ksl.std.time

import java.time.Duration

fun Int.ticks(): Long = toLong()
fun Long.ticks(): Long = this

fun Int.secondsToTicks(): Long = Math.multiplyExact(toLong(), 20L)
fun Long.secondsToTicks(): Long = Math.multiplyExact(this, 20L)

fun Int.minutesToTicks(): Long = Math.multiplyExact(toLong(), 1_200L)
fun Long.minutesToTicks(): Long = Math.multiplyExact(this, 1_200L)

fun Int.hoursToTicks(): Long = Math.multiplyExact(toLong(), 72_000L)
fun Long.hoursToTicks(): Long = Math.multiplyExact(this, 72_000L)

/** Converts a non-negative duration to ticks, rounding any partial tick upward. */
fun Duration.toTicksCeiling(): Long {
    require(!isNegative) { "duration must not be negative" }
    val wholeSeconds = Math.multiplyExact(seconds, 20L)
    val fractionalTicks = (nano.toLong() * 20L + 999_999_999L) / 1_000_000_000L
    return Math.addExact(wholeSeconds, fractionalTicks)
}

/** Converts ticks to a duration using the 20 ticks-per-second server clock. */
fun Long.ticksToDuration(): Duration =
    Duration.ofMillis(Math.multiplyExact(this, 50L))
