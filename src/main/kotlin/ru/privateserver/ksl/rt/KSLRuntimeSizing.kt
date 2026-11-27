package ru.privateserver.ksl.rt

/**
 * Conservative defaults for KSL-managed concurrency and retained compilation artifacts.
 *
 * These values only size resources owned by KSL. They do not cap arbitrary objects created
 * by scripts, Bukkit/Paper, other plugins, native libraries, or the JVM itself.
 */
internal object KSLRuntimeSizing {
    private const val GIB = 1024L * 1024L * 1024L

    fun recommendedWorkerCount(maxHeapBytes: Long, availableProcessors: Int): Int {
        val cpuLimit = (availableProcessors.coerceAtLeast(1) - 1).coerceAtLeast(1).coerceAtMost(8)
        val memoryLimit = when {
            maxHeapBytes <= 2L * GIB -> 1
            maxHeapBytes <= 4L * GIB -> 2
            maxHeapBytes <= 8L * GIB -> 4
            else -> 6
        }
        return minOf(cpuLimit, memoryLimit).coerceAtLeast(1)
    }

    fun recommendedQueueCapacity(maxHeapBytes: Long): Int = when {
        maxHeapBytes <= 2L * GIB -> 64
        maxHeapBytes <= 4L * GIB -> 128
        maxHeapBytes <= 8L * GIB -> 256
        else -> 512
    }

    fun recommendedCompileCacheEntries(maxHeapBytes: Long): Int = when {
        maxHeapBytes <= 2L * GIB -> 32
        maxHeapBytes <= 4L * GIB -> 64
        maxHeapBytes <= 8L * GIB -> 96
        else -> 128
    }
}
