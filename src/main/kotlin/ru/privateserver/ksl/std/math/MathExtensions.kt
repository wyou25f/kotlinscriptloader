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
    val fraction = (this - inputMinimum) / (inputMaximum - inputMinimum)
    return outputMinimum + fraction * (outputMaximum - outputMinimum)
}

/** Returns true with the requested probability. The percentage must be between 0 and 100. */
fun randomChance(percent: Double): Boolean {
    require(percent.isFinite() && percent in 0.0..100.0) {
        "percent must be finite and between 0 and 100"
    }
    return ThreadLocalRandom.current().nextDouble(100.0) < percent
}
