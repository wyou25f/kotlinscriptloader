package ru.privateserver.ksl.rt

/**
 * Conservative defaults for KSL-managed concurrency and retained compilation artifacts.
 *
 * These values only size resources owned by KSL. They do not cap arbitrary objects created
 * by scripts, Bukkit/Paper, other plugins, native libraries, or the JVM itself.
 */
internal object KSLRuntimeSizing {
    private const val GIB = 1024L * 1024L * 1024L

    /**
     * Uses the smaller known memory boundary. This protects small physical hosts even when
     * the JVM was accidentally started with an oversized -Xmx setting.
     */
    fun effectiveMemoryLimitBytes(maxHeapBytes: Long, physicalMemoryBytes: Long): Long {
        val heap = maxHeapBytes.takeIf { it > 0L }
        val physical = physicalMemoryBytes.takeIf { it > 0L }
        return when {
            heap != null && physical != null -> minOf(heap, physical)
            heap != null -> heap
            physical != null -> physical
            else -> 0L
        }
    }

    fun recommendedWorkerCount(maxHeapBytes: Long, availableProcessors: Int): Int =
        recommendedWorkerCount(maxHeapBytes, -1L, availableProcessors)

    fun recommendedWorkerCount(
        maxHeapBytes: Long,
        physicalMemoryBytes: Long,
        availableProcessors: Int
    ): Int {
        val memoryBudget = effectiveMemoryLimitBytes(maxHeapBytes, physicalMemoryBytes)
        val cpuLimit = (availableProcessors.coerceAtLeast(1) - 1).coerceAtLeast(1).coerceAtMost(8)
        val memoryLimit = when {
            memoryBudget <= 2L * GIB -> 1
            memoryBudget <= 4L * GIB -> 2
            memoryBudget <= 8L * GIB -> 4
            else -> 6
        }
        return minOf(cpuLimit, memoryLimit).coerceAtLeast(1)
    }

    fun recommendedQueueCapacity(maxHeapBytes: Long): Int =
        recommendedQueueCapacity(maxHeapBytes, physicalMemoryBytes = -1L)

    fun recommendedQueueCapacity(maxHeapBytes: Long, physicalMemoryBytes: Long): Int {
        val memoryBudget = effectiveMemoryLimitBytes(maxHeapBytes, physicalMemoryBytes)
        return when {
            memoryBudget <= 2L * GIB -> 64
            memoryBudget <= 4L * GIB -> 128
            memoryBudget <= 8L * GIB -> 256
            else -> 512
        }
    }

    fun recommendedCompileCacheCapacity(
        baseCapacity: Int,
        heapUsedBytes: Long,
        heapMaxBytes: Long
    ): Int {
        require(baseCapacity > 0) { "baseCapacity must be positive" }
        if (heapMaxBytes <= 0L) return baseCapacity
        val pressure = heapUsedBytes.coerceAtLeast(0L).toDouble() / heapMaxBytes.toDouble()
        val target = when {
            pressure >= 0.90 -> maxOf(16, baseCapacity / 4)
            pressure >= 0.82 -> maxOf(16, baseCapacity / 2)
            pressure >= 0.72 -> maxOf(16, baseCapacity * 3 / 4)
            else -> baseCapacity
        }
        return minOf(baseCapacity, target)
    }

    fun recommendedCompileCacheEntries(maxHeapBytes: Long): Int =
        recommendedCompileCacheEntries(maxHeapBytes, physicalMemoryBytes = -1L)

    fun recommendedCompileCacheEntries(maxHeapBytes: Long, physicalMemoryBytes: Long): Int {
        val memoryBudget = effectiveMemoryLimitBytes(maxHeapBytes, physicalMemoryBytes)
        return when {
            memoryBudget <= 2L * GIB -> 32
            memoryBudget <= 4L * GIB -> 64
            memoryBudget <= 8L * GIB -> 96
            else -> 128
        }
    }
}
