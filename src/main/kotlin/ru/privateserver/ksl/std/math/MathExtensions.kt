package ru.privateserver.ksl.std.math

import java.util.concurrent.ThreadLocalRandom

fun Int.clamped(minimum: Int, maximum: Int): Int {
    require(minimum <= maximum) { "minimum must not exceed maximum" }
    return coerceIn(minimum, maximum)
}

fun Long.clamped(minimum: Long, maximum: Long): Long {
    require(minimum <= maximum) { "minimum must not exceed maximum" }
    return coerceIn(minimum, maximum)
}

fun Double.clamped(minimum: Double, maximum: Double): Double {
    require(!minimum.isNaN() && !maximum.isNaN() && minimum <= maximum) {
        "bounds must not be NaN and minimum must not exceed maximum"
    }
    require(!isNaN()) { "value must not be NaN" }
    return coerceIn(minimum, maximum)
}

/** Maps a value between ranges. Values outside the input range are mapped linearly too. */
fun Double.mapToRange(
    inputMinimum: Double,
    inputMaximum: Double,
    outputMinimum: Double,
    outputMaximum: Double
): Double {
    require(inputMinimum.isFinite() && inputMaximum.isFinite()) { "input bounds must be finite" }
    require(outputMinimum.isFinite() && outputMaximum.isFinite()) { "output bounds must be finite" }
    require(inputMinimum != inputMaximum) { "input range must not be empty" }
    require(isFinite()) { "value must be finite" }

    val range = inputMaximum - inputMinimum
    val delta = this - inputMinimum
    val fraction = if (range.isFinite() && delta.isFinite()) {
        delta / range
    } else {
        (this / 2.0 - inputMinimum / 2.0) / (inputMaximum / 2.0 - inputMinimum / 2.0)
    }
    require(fraction.isFinite()) { "mapped fraction overflowed" }

    val result = outputMinimum * (1.0 - fraction) + outputMaximum * fraction
    if (!result.isFinite()) throw ArithmeticException("mapped value is outside the finite Double range")
    return result
}

/** Returns true with the requested probability. The percentage must be between 0 and 100. */
fun randomChance(percent: Double): Boolean {
    require(percent.isFinite() && percent in 0.0..100.0) {
        "percent must be finite and between 0 and 100"
    }
    return ThreadLocalRandom.current().nextDouble(100.0) < percent
}

/** Linear interpolation with finite inputs; progress may be outside 0..1 for extrapolation. */
fun lerp(start: Double, end: Double, progress: Double): Double {
    require(start.isFinite() && end.isFinite() && progress.isFinite()) {
        "lerp inputs must be finite"
    }
    if (start == end) return start
    val result = start * (1.0 - progress) + end * progress
    if (!result.isFinite()) throw ArithmeticException("lerp result is outside the finite Double range")
    return result
}

/** Returns the relative position of [value] in the input range. Optionally clamps to 0..1. */
fun inverseLerp(start: Double, end: Double, value: Double, clamp: Boolean = false): Double {
    require(start.isFinite() && end.isFinite() && value.isFinite()) {
        "inverseLerp inputs must be finite"
    }
    require(start != end) { "input range must not be empty" }

    val range = end - start
    val delta = value - start
    val fraction = if (range.isFinite() && delta.isFinite()) {
        delta / range
    } else {
        (value / 2.0 - start / 2.0) / (end / 2.0 - start / 2.0)
    }
    require(fraction.isFinite()) { "inverseLerp fraction overflowed" }
    return if (clamp) fraction.coerceIn(0.0, 1.0) else fraction
}

/** Random integer in [minimumInclusive, maximumExclusive). */
fun randomInt(minimumInclusive: Int, maximumExclusive: Int): Int {
    require(minimumInclusive < maximumExclusive) { "minimumInclusive must be less than maximumExclusive" }
    return ThreadLocalRandom.current().nextInt(minimumInclusive, maximumExclusive)
}

/** Random long in [minimumInclusive, maximumExclusive). */
fun randomLong(minimumInclusive: Long, maximumExclusive: Long): Long {
    require(minimumInclusive < maximumExclusive) { "minimumInclusive must be less than maximumExclusive" }
    return ThreadLocalRandom.current().nextLong(minimumInclusive, maximumExclusive)
}

/** Random finite double in [minimumInclusive, maximumExclusive). */
fun randomDouble(minimumInclusive: Double, maximumExclusive: Double): Double {
    require(minimumInclusive.isFinite() && maximumExclusive.isFinite() && minimumInclusive < maximumExclusive) {
        "random bounds must be finite and minimumInclusive must be less than maximumExclusive"
    }
    return ThreadLocalRandom.current().nextDouble(minimumInclusive, maximumExclusive)
}
