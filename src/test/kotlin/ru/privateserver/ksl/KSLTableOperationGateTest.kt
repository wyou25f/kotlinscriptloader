package ru.privateserver.ksl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class KSLTableOperationGateTest {
    @Test
    fun keepsWriteSubmissionAheadOfTheNextDeleteBarrier() {
        val gate = KSLTableOperationGate()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val deleteAttempting = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val writer = Thread {
            try {
                gate.withLock {
                    events += "write-offer"
                    writeEntered.countDown()
                    check(releaseWrite.await(5, TimeUnit.SECONDS)) { "writer timed out" }
                    events += "write-submit"
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }
        val deleter = Thread {
            try {
                check(writeEntered.await(5, TimeUnit.SECONDS)) { "writer did not enter gate" }
                deleteAttempting.countDown()
                gate.withLock {
                    events += "delete-barrier"
                    events += "delete-submit"
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }

        try {
            writer.start()
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
            deleter.start()
            assertTrue(deleteAttempting.await(5, TimeUnit.SECONDS))

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (deleter.isAlive && deleter.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, deleter.state)
        } finally {
            releaseWrite.countDown()
            writer.join(5_000)
            if (deleter.isAlive) deleter.join(5_000)
        }

        assertFalse(writer.isAlive)
        assertFalse(deleter.isAlive)
        failure.get()?.let { throw IllegalStateException("Concurrent table operation failed", it) }
        assertEquals(
            listOf("write-offer", "write-submit", "delete-barrier", "delete-submit"),
            events.toList()
        )
    }
}
