package ru.privateserver.ksl.kslshu

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.rt.KSLWorkScheduler
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KSLTaskSchedulerHubTest {
    @Test
    fun `official scheduler executes and completes a task`() {
        val workers = KSLWorkScheduler(1, 8)
        val scheduler = KSLOfficialTaskScheduler(workers)
        try {
            val latch = CountDownLatch(1)
            val handle = scheduler.schedule(KSLTaskSpec("test-script") { latch.countDown() })
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(KSLTaskState.SUCCEEDED, handle.completion.get(2, TimeUnit.SECONDS).state)
            assertEquals("test-script", handle.owner)
        } finally {
            scheduler.close()
            workers.close()
        }
    }

    @Test
    fun `scope binds owner and supports delayed work`() {
        val workers = KSLWorkScheduler(1, 8)
        val scope = KSLTaskScope(KSLOfficialTaskScheduler(workers), "economy")
        try {
            val latch = CountDownLatch(1)
            val handle = scope.schedule(Duration.ofMillis(10)) { latch.countDown() }
            assertEquals("economy", handle.owner)
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(KSLTaskState.SUCCEEDED, handle.completion.get(2, TimeUnit.SECONDS).state)
        } finally {
            workers.close()
        }
    }

    @Test
    fun `registry rejects duplicate and malformed identifiers`() {
        val workers = KSLWorkScheduler(1, 8)
        try {
            val registry = KSLTaskSchedulerRegistry.withOfficial(KSLTaskSchedulerEnvironment(workers))
            assertTrue("ksl" in registry.available())
            assertThrows(IllegalArgumentException::class.java) {
                registry.register("ksl") { KSLOfficialTaskScheduler(it.officialWorkers) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                registry.register("../unsafe") { KSLOfficialTaskScheduler(it.officialWorkers) }
            }
            assertNotNull(registry.create("ksl"))
        } finally {
            workers.close()
        }
    }

    @Test
    fun `task spec rejects invalid intervals`() {
        assertThrows(IllegalArgumentException::class.java) {
            KSLTaskSpec("test", interval = Duration.ZERO) { }
        }
        assertThrows(IllegalArgumentException::class.java) {
            KSLTaskSpec("test", delay = Duration.ofMillis(-1)) { }
        }
    }
}
