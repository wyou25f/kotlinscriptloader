package ru.privateserver.ksl.kslshu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.privateserver.ksl.rt.KSLRuntimeScope
import ru.privateserver.ksl.rt.KSLWorkSnapshot
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

class KSLTaskScopeGenerationTest {

    private class RecordingScheduler : KSLTaskScheduler {
        private val ids = AtomicLong()
        val submitted = mutableListOf<KSLTaskSpec>()
        val cancelled = mutableListOf<Long>()

        override fun schedule(spec: KSLTaskSpec): KSLTaskHandle {
            submitted += spec
            val id = ids.incrementAndGet()
            val completion = CompletableFuture<KSLTaskResult>()
            return KSLTaskHandle(id, spec.owner, completion) { mayInterrupt ->
                cancelled += id
                completion.cancel(mayInterrupt)
                true
            }
        }

        override fun cancel(owner: String, taskId: Long, mayInterruptIfRunning: Boolean): Boolean {
            cancelled += taskId
            return true
        }

        override fun cancelOwner(owner: String): Int = 0

        override fun snapshot(): KSLWorkSnapshot =
            KSLWorkSnapshot(workers = 0, active = 0, queued = 0, tracked = 0, completed = 0, rejected = 0)

        override fun close() = Unit
    }

    @Test
    fun `generation closure cancels owned tasks and invalidates retained scheduler facades`() {
        val runtimeScope = KSLRuntimeScope("script:shop#generation:1")
        val scheduler = RecordingScheduler()
        val facade = KSLTaskScope(scheduler, "shop", runtimeScope)

        val handle = facade.schedule(
            KSLTaskSpec(owner = "untrusted-owner", delay = Duration.ofHours(1)) { }
        )

        assertEquals("shop", handle.owner)
        assertEquals(1, scheduler.submitted.size)

        runtimeScope.close()

        assertTrue(handle.completion.isCancelled)
        assertEquals(listOf(handle.id), scheduler.cancelled)
        assertThrows(IllegalStateException::class.java) {
            facade.schedule(KSLTaskSpec(owner = "shop") { })
        }
        assertThrows(IllegalStateException::class.java) { facade.cancel(handle.id) }
        assertThrows(IllegalStateException::class.java) { facade.cancelAll() }
        assertThrows(IllegalStateException::class.java) { facade.snapshot() }

        // A custom scheduler may dequeue concurrently with cancellation. The action
        // wrapper still refuses to execute old-generation code after closure.
        assertThrows(IllegalStateException::class.java) {
            scheduler.submitted.single().action(KSLTaskContext("shop", 1L, 0L))
        }
    }
}
