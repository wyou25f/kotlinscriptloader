package ru.privateserver.ksl.accounts

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class KSLAuthAttemptLimiterTest {
    @Test
    fun allowsConfiguredAttemptsThenBlocksUntilLockoutExpires() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = KSLAuthAttemptLimiter(
            maxAttempts = 3,
            windowMillis = 60_000,
            lockoutMillis = 5_000,
            clock = clock
        )

        repeat(3) { assertTrue(limiter.tryAcquire("user:alice")) }
        assertFalse(limiter.tryAcquire("user:alice"))
        clock.advanceMillis(4_999)
        assertFalse(limiter.tryAcquire("user:alice"))
        clock.advanceMillis(1)
        assertTrue(limiter.tryAcquire("user:alice"))
    }

    @Test
    fun successfulAuthenticationClearsTheAttemptWindow() {
        val limiter = KSLAuthAttemptLimiter(maxAttempts = 2)
        assertTrue(limiter.tryAcquire("user:alice"))
        assertTrue(limiter.tryAcquire("user:alice"))
        assertFalse(limiter.tryAcquire("user:alice"))

        limiter.recordSuccess("user:alice")
        assertTrue(limiter.tryAcquire("user:alice"))
    }

    @Test
    fun keyTableIsBoundedAndExpiredEntriesArePruned() {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = KSLAuthAttemptLimiter(
            maxAttempts = 2,
            windowMillis = 1_000,
            lockoutMillis = 2_000,
            maxTrackedKeys = 1,
            clock = clock
        )

        assertTrue(limiter.tryAcquire("user:one"))
        assertFalse(limiter.tryAcquire("user:two"))
        assertEquals(1, limiter.trackedKeyCount())
        clock.advanceMillis(1_000)
        assertTrue(limiter.tryAcquire("user:two"))
        assertEquals(1, limiter.trackedKeyCount())
    }

    @Test
    fun rejectsBlankAndOversizedKeysAndInvalidSettings() {
        val limiter = KSLAuthAttemptLimiter()
        assertThrows(IllegalArgumentException::class.java) { limiter.tryAcquire(" ") }
        assertThrows(IllegalArgumentException::class.java) { limiter.tryAcquire("x".repeat(257)) }
        assertThrows(IllegalArgumentException::class.java) { limiter.recordSuccess("") }
        assertThrows(IllegalArgumentException::class.java) { KSLAuthAttemptLimiter(maxAttempts = 0) }
        assertThrows(IllegalArgumentException::class.java) { KSLAuthAttemptLimiter(maxTrackedKeys = 0) }
    }

    private class MutableClock(
        private var current: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
        override fun instant(): Instant = current
        fun advanceMillis(millis: Long) { current = current.plusMillis(millis) }
    }
}
