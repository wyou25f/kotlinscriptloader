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

fun Int.daysToTicks(): Long = Math.multiplyExact(toLong(), 1_728_000L)
fun Long.daysToTicks(): Long = Math.multiplyExact(this, 1_728_000L)

fun Int.weeksToTicks(): Long = Math.multiplyExact(toLong(), 12_096_000L)
fun Long.weeksToTicks(): Long = Math.multiplyExact(this, 12_096_000L)

/** Converts milliseconds to whole ticks, rounding toward negative infinity. */
fun Long.millisecondsToTicksFloor(): Long {
    val quotient = this / 50L
    return if (this % 50L < 0L) Math.subtractExact(quotient, 1L) else quotient
}

/** Converts milliseconds to whole ticks, rounding toward positive infinity. */
fun Long.millisecondsToTicksCeiling(): Long {
    val quotient = this / 50L
    return if (this % 50L > 0L) Math.addExact(quotient, 1L) else quotient
}

fun Int.millisecondsToTicksFloor(): Long = toLong().millisecondsToTicksFloor()
fun Int.millisecondsToTicksCeiling(): Long = toLong().millisecondsToTicksCeiling()

/** Converts a non-negative duration to ticks, rounding any partial tick upward. */
fun Duration.toTicksCeiling(): Long {
    require(!isNegative) { "duration must not be negative" }
    val wholeSeconds = Math.multiplyExact(seconds, 20L)
    val fractionalTicks = (nano.toLong() * 20L + 999_999_999L) / 1_000_000_000L
    return Math.addExact(wholeSeconds, fractionalTicks)
}

/** Converts a non-negative duration to whole ticks, rounding partial ticks downward. */
fun Duration.toTicksFloor(): Long {
    require(!isNegative) { "duration must not be negative" }
    return Math.addExact(Math.multiplyExact(seconds, 20L), nano.toLong() / 50_000_000L)
}

/** Converts ticks to a duration using the 20 ticks-per-second server clock. */
fun Long.ticksToDuration(): Duration =
    Duration.ofMillis(Math.multiplyExact(this, 50L))
