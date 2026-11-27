package ru.privateserver.ksl.kslshu

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.rt.KSLWorkScheduler
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

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
    fun `scope overrides task spec owner to prevent owner spoofing`() {
        val workers = KSLWorkScheduler(1, 8)
        val scope = KSLTaskScope(KSLOfficialTaskScheduler(workers), "trusted-owner")
        try {
            val latch = CountDownLatch(1)
            val handle = scope.schedule(KSLTaskSpec("untrusted-owner") { latch.countDown() })
            assertEquals("trusted-owner", handle.owner)
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
    fun `registry creates addon supplied scheduler engines`() {
        val workers = KSLWorkScheduler(1, 8)
        var factoryCalls = 0
        var closed = false
        try {
            val registry = KSLTaskSchedulerRegistry.withOfficial(KSLTaskSchedulerEnvironment(workers))
            val registration = registry.register("example-addon.engine") { environment ->
                factoryCalls++
                val delegate = KSLOfficialTaskScheduler(environment.officialWorkers)
                object : KSLTaskScheduler by delegate {
                    override fun close() {
                        closed = true
                        delegate.close()
                    }
                }
            }

            assertTrue("example-addon.engine" in registry.available())
            val custom = registry.create("example-addon.engine")
            assertEquals(1, factoryCalls)
            val latch = CountDownLatch(1)
            val result = custom.schedule(KSLTaskSpec("addon:example") { latch.countDown() })
            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(KSLTaskState.SUCCEEDED, result.completion.get(2, TimeUnit.SECONDS).state)
            custom.close()
            assertTrue(closed)
            registration.close()
            assertFalse("example-addon.engine" in registry.available())
            assertThrows(IllegalArgumentException::class.java) {
                registry.create("example-addon.engine")
            }
        } finally {
            workers.close()
        }
    }

    @Test
    fun `task scopes receive isolated owners and cannot cancel each others work`() {
        val workers = KSLWorkScheduler(1, 8)
        val scheduler = KSLOfficialTaskScheduler(workers)
        val first = KSLTaskScope(scheduler, "addon:one")
        val second = KSLTaskScope(scheduler, "addon:two")
        try {
            val firstTask = first.schedule(Duration.ofHours(1)) { }
            val secondTask = second.schedule(Duration.ofHours(1)) { }
            assertNotEquals(firstTask.owner, secondTask.owner)
            assertTrue(first.cancelAll() >= 1)
            assertFalse(secondTask.completion.isDone)
            assertTrue(second.cancel(secondTask.id))
        } finally {
            workers.close()
        }
    }

    @Test
    fun `isolated factory creates independent bounded worker engines`() {
        val sharedWorkers = KSLWorkScheduler(1, 4)
        val factory = KSLTaskSchedulerFactory.isolated(
            KSLTaskSchedulerOptions(workers = 1, queueCapacity = 1, perOwnerLimit = 3)
        )
        val first = factory.create(KSLTaskSchedulerEnvironment(sharedWorkers))
        val second = factory.create(KSLTaskSchedulerEnvironment(sharedWorkers))
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondRan = CountDownLatch(1)

        try {
            val firstHandle = first.schedule(KSLTaskSpec("first") {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            })
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

            val secondHandle = second.schedule(KSLTaskSpec("second") { secondRan.countDown() })
            assertTrue(secondRan.await(2, TimeUnit.SECONDS))
            assertEquals(KSLTaskState.SUCCEEDED, secondHandle.completion.get(2, TimeUnit.SECONDS).state)
            assertEquals(1, first.snapshot().running)
            assertEquals(0, second.snapshot().running)

            val queued = first.schedule(KSLTaskSpec("first") { })
            assertThrows(RejectedExecutionException::class.java) {
                first.schedule(KSLTaskSpec("first") { })
            }
            assertFalse(queued.completion.isDone, "the bounded queue should retain one pending task")
            assertFalse(firstHandle.completion.isDone, "the running task should remain independent")
        } finally {
            releaseFirst.countDown()
            first.close()
            second.close()
            sharedWorkers.close()
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

    @Test
    fun `registry does not hold its lock while creating scheduler engines`() {
        val workers = KSLWorkScheduler(1, 8)
        val registry = KSLTaskSchedulerRegistry.withOfficial(KSLTaskSchedulerEnvironment(workers))
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val factoryRegistration = registry.register("blocking") { environment ->
            factoryEntered.countDown()
            check(releaseFactory.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release test factory" }
            KSLOfficialTaskScheduler(environment.officialWorkers)
        }
        var createdScheduler: KSLTaskScheduler? = null

        try {
            val creation = executor.submit<KSLTaskScheduler> {
                registry.create("blocking")
            }
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))

            // Registry operations must remain available while third-party factory
            // initialization is blocked on another thread.
            val registration = executor.submit<AutoCloseable> {
                registry.register("during-create") { environment ->
                    KSLOfficialTaskScheduler(environment.officialWorkers)
                }
            }.get(5, TimeUnit.SECONDS)

            assertTrue("during-create" in registry.available())
            registration.close()
            assertFalse("during-create" in registry.available())

            releaseFactory.countDown()
            createdScheduler = creation.get(5, TimeUnit.SECONDS)
        } finally {
            releaseFactory.countDown()
            runCatching { createdScheduler?.close() }
            factoryRegistration.close()
            executor.shutdownNow()
            workers.close()
        }
    }

}
