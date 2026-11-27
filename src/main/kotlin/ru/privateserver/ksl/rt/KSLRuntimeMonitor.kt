package ru.privateserver.ksl.rt

import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory

data class KSLRuntimeMemorySnapshot(
    val heapUsedBytes: Long,
    val heapCommittedBytes: Long,
    val heapMaxBytes: Long,
    val nonHeapUsedBytes: Long,
    val directBufferBytes: Long,
    val mappedBufferBytes: Long,
    val loadedClasses: Int,
    val totalLoadedClasses: Long,
    val unloadedClasses: Long,
    val physicalMemoryBytes: Long,
    val freePhysicalMemoryBytes: Long
)

data class KSLRuntimeSnapshot(
    val heapUsedBytes: Long,
    val heapCommittedBytes: Long,
    val heapMaxBytes: Long,
    val nonHeapUsedBytes: Long,
    val processors: Int,
    val processCpuLoad: Double?,
    val systemCpuLoad: Double?,
    val systemLoadAverage: Double,
    val liveThreads: Int,
    val daemonThreads: Int,
    val gcCount: Long,
    val gcTimeMillis: Long
) {
    val heapUsedPercent: Double?
        get() = heapMaxBytes.takeIf { it > 0L }?.let { heapUsedBytes * 100.0 / it }
}

/**
 * Read-only JVM/host telemetry. It deliberately does not call System.gc() or
 * attempt to resize the JVM heap: those are controlled by the JVM launch flags.
 */
object KSLRuntimeMonitor {
    /**
     * JVM memory details that complement heap usage with direct/mapped buffers,
     * class-loading counters and best-effort host/container memory information.
     * Unsupported platform measurements are reported as -1.
     */
    @JvmStatic
    fun memorySnapshot(): KSLRuntimeMemorySnapshot {
        val memory = ManagementFactory.getMemoryMXBean()
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
        fun bufferBytes(prefix: String): Long {
            val matching = pools.filter { it.name.startsWith(prefix, ignoreCase = true) }
            return if (matching.isEmpty()) -1L else matching.sumOf { it.memoryUsed.coerceAtLeast(0L) }
        }
        val classes = ManagementFactory.getClassLoadingMXBean()
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        return KSLRuntimeMemorySnapshot(
            heapUsedBytes = heap.used.coerceAtLeast(0L),
            heapCommittedBytes = heap.committed.coerceAtLeast(0L),
            heapMaxBytes = heap.max,
            nonHeapUsedBytes = nonHeap.used.coerceAtLeast(0L),
            directBufferBytes = bufferBytes("direct"),
            mappedBufferBytes = bufferBytes("mapped"),
            loadedClasses = classes.loadedClassCount,
            totalLoadedClasses = classes.totalLoadedClassCount,
            unloadedClasses = classes.unloadedClassCount,
            physicalMemoryBytes = runCatching { os?.totalMemorySize ?: -1L }.getOrDefault(-1L),
            freePhysicalMemoryBytes = runCatching { os?.freeMemorySize ?: -1L }.getOrDefault(-1L)
        )
    }

    @JvmStatic
    fun snapshot(): KSLRuntimeSnapshot {
        val memory = ManagementFactory.getMemoryMXBean()
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val threads = ManagementFactory.getThreadMXBean()
        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val os = ManagementFactory.getOperatingSystemMXBean()
        val extended = os as? com.sun.management.OperatingSystemMXBean
        return KSLRuntimeSnapshot(
            heapUsedBytes = heap.used.coerceAtLeast(0L),
            heapCommittedBytes = heap.committed.coerceAtLeast(0L),
            heapMaxBytes = heap.max,
            nonHeapUsedBytes = nonHeap.used.coerceAtLeast(0L),
            processors = os.availableProcessors.coerceAtLeast(1),
            processCpuLoad = extended?.processCpuLoad?.takeIf { it >= 0.0 },
            systemCpuLoad = extended?.cpuLoad?.takeIf { it >= 0.0 },
            systemLoadAverage = os.systemLoadAverage,
            liveThreads = threads.threadCount,
            daemonThreads = threads.daemonThreadCount,
            gcCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0L) },
            gcTimeMillis = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0L) }
        )
    }
}
