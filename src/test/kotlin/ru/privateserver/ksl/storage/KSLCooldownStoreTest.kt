package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class KSLCooldownStoreTest {

    @Test
    fun `zero cooldown allows every call and negative values are rejected`() {
        val store = KSLCooldownStore()

        assertTrue(store.tryUse("script:player", 0))
        assertTrue(store.tryUse("script:player", 0))
        assertEquals(0L, store.remaining("script:player", 0))
        assertThrows(IllegalArgumentException::class.java) {
            store.tryUse("script:player", -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.remaining("script:player", -1)
        }
    }

    @Test
    fun `very large cooldown does not overflow into an immediate expiry`() {
        val store = KSLCooldownStore()

        assertTrue(store.tryUse("script:player", Long.MAX_VALUE))
        assertFalse(store.tryUse("script:player", Long.MAX_VALUE))
        assertTrue(store.remaining("script:player", Long.MAX_VALUE) > 0L)
    }

    @Test
    fun `only one concurrent caller can consume a cooldown`() {
        val store = KSLCooldownStore()
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        try {
            val tasks = (1..32).map {
                Callable {
                    start.await()
                    store.tryUse("script:shared-player", 60)
                }
            }
            val futures = tasks.map { workers.submit(it) }
            start.countDown()

            assertEquals(1, futures.count { it.get() })
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `reset and script cleanup remove cooldown entries`() {
        val store = KSLCooldownStore()

        assertTrue(store.tryUse("one:player", 60))
        assertTrue(store.tryUse("two:player", 60))
        store.reset("one:player")
        assertTrue(store.tryUse("one:player", 60))
        store.clearScript("two")
        assertTrue(store.tryUse("two:player", 60))
    }
}
