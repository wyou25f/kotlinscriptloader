package ru.privateserver.ksl.accounts

import ru.privateserver.ksl.std.security.KSLSecurity
import java.time.Clock
import java.util.LinkedHashMap

/**
 * Bounded, process-local limiter for authentication attempts.
 *
 * Call [tryAcquire] before password verification; every allowed call consumes one attempt.
 * Call [recordSuccess] after a successful authentication to clear the key's failure window.
 * Keys are SHA-256 digests in memory, not retained in plaintext. The limiter is not shared
 * between server processes and must be combined with a durable or edge limiter for public services.
 */
class KSLAuthAttemptLimiter @JvmOverloads constructor(
    private val maxAttempts: Int = 5,
    private val windowMillis: Long = 60_000,
    private val lockoutMillis: Long = 300_000,
    private val maxTrackedKeys: Int = 10_000,
    private val clock: Clock = Clock.systemUTC()
) {
    private data class AttemptWindow(
        val startedAt: Long,
        var attempts: Int,
        var blockedUntil: Long = 0
    )

    private val windows = LinkedHashMap<String, AttemptWindow>()

    init {
        require(maxAttempts in 1..1_000) { "maxAttempts must be between 1 and 1000" }
        require(windowMillis >= 1_000L && windowMillis <= 86_400_000L) { "windowMillis must be between 1000 and 86400000" }
        require(lockoutMillis >= 1_000L && lockoutMillis <= 604_800_000L) { "lockoutMillis must be between 1000 and 604800000" }
        require(maxTrackedKeys in 1..100_000) { "maxTrackedKeys must be between 1 and 100000" }
    }

    /**
     * Atomically consumes one attempt when allowed. The first [maxAttempts] calls in a window
     * succeed; subsequent calls are denied until [lockoutMillis] elapses.
     *
     * When the bounded key table is full, new keys fail closed until expired entries are pruned.
     */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val digest = digestKey(key)
        val now = clock.millis()
        pruneExpired(now)

        val existing = windows[digest]
        if (existing != null) {
            if (existing.blockedUntil > now) return false
            if (existing.attempts >= maxAttempts) {
                existing.blockedUntil = safeAdd(now, lockoutMillis)
                return false
            }
            existing.attempts++
            return true
        }

        if (windows.size >= maxTrackedKeys) return false
        windows[digest] = AttemptWindow(now, attempts = 1)
        return true
    }

    /** Clears the key's attempt window after successful authentication. */
    @Synchronized
    fun recordSuccess(key: String) {
        windows.remove(digestKey(key))
    }

    /** Number of currently tracked hashed keys; useful for metrics and diagnostics. */
    @Synchronized
    fun trackedKeyCount(): Int = windows.size

    private fun pruneExpired(now: Long) {
        val iterator = windows.entries.iterator()
        while (iterator.hasNext()) {
            val window = iterator.next().value
            val lockoutExpired = window.blockedUntil > 0 && window.blockedUntil <= now
            val windowExpired = window.blockedUntil == 0L &&
                now >= window.startedAt && now - window.startedAt >= windowMillis
            if (lockoutExpired || windowExpired) iterator.remove()
        }
    }

    private fun digestKey(key: String): String {
        require(key.isNotBlank() && key.length <= 256) {
            "Rate-limit key must contain 1..256 non-whitespace characters"
        }
        return KSLSecurity.sha256(key.trim())
    }

    private fun safeAdd(first: Long, second: Long): Long =
        try {
            Math.addExact(first, second)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
}
