package example.addon

import ru.privateserver.ksl.api.KSLTaskHandle
import ru.privateserver.ksl.api.KSLTaskScheduler
import ru.privateserver.ksl.api.KSLTaskSchedulerEnvironment
import ru.privateserver.ksl.api.KSLTaskSpec
import ru.privateserver.ksl.api.KSLWorkSnapshot
import ru.privateserver.ksl.api.KSLOfficialTaskScheduler
import java.util.concurrent.atomic.AtomicLong

/**
 * Example custom engine adapter.
 *
 * This version delegates execution to KSL's bounded worker pool and adds
 * engine-local metrics. Replace the delegate with your own bounded executor,
 * tick queue or external scheduler to implement an independent execution engine.
 */
class ExampleScheduler(environment: KSLTaskSchedulerEnvironment) : KSLTaskScheduler {
    private val delegate = KSLOfficialTaskScheduler(environment.officialWorkers)
    private val submitted = AtomicLong()

    fun submittedCount(): Long = submitted.get()

    override fun schedule(spec: KSLTaskSpec): KSLTaskHandle {
        submitted.incrementAndGet()
        return delegate.schedule(spec)
    }

    override fun cancel(owner: String, taskId: Long, mayInterruptIfRunning: Boolean): Boolean =
        delegate.cancel(owner, taskId, mayInterruptIfRunning)

    override fun cancelOwner(owner: String): Int = delegate.cancelOwner(owner)

    override fun snapshot(): KSLWorkSnapshot = delegate.snapshot()

    override fun close() {
        // The official adapter does not own the shared worker pool.
        // Close only resources created by this adapter.
        delegate.close()
    }
}
