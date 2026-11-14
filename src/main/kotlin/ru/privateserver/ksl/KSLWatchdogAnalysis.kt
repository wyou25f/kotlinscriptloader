package ru.privateserver.ksl

/** Pure helpers for comparing watchdog snapshots without depending on Bukkit or JVM state. */
internal object KSLWatchdogAnalysis {
    /** Returns frames that are identical at the top of two stack snapshots. */
    fun commonPrefix(first: List<String>, second: List<String>): List<String> =
        first.zip(second).takeWhile { (left, right) -> left == right }.map { it.first }

    fun skippedThreads(total: Int, captured: Int): Int =
        (total - captured).coerceAtLeast(0)
}