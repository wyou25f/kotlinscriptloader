package ru.privateserver.ksl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KSLRuntimeSizingTest {
    private val gib = 1024L * 1024L * 1024L

    @Test
    fun `four gigabyte heaps use conservative worker and queue defaults`() {
        assertEquals(2, KSLRuntimeSizing.recommendedWorkerCount(4L * gib, 16))
        assertEquals(128, KSLRuntimeSizing.recommendedQueueCapacity(4L * gib))
        assertEquals(64, KSLRuntimeSizing.recommendedCompileCacheEntries(4L * gib))
    }

    @Test
    fun `small heaps avoid multiplying compilation and worker pressure`() {
        assertEquals(1, KSLRuntimeSizing.recommendedWorkerCount(2L * gib, 32))
        assertEquals(64, KSLRuntimeSizing.recommendedQueueCapacity(2L * gib))
        assertEquals(32, KSLRuntimeSizing.recommendedCompileCacheEntries(2L * gib))
    }

    @Test
    fun `larger heaps scale within a conservative processor bound`() {
        assertEquals(4, KSLRuntimeSizing.recommendedWorkerCount(8L * gib, 16))
        assertEquals(256, KSLRuntimeSizing.recommendedQueueCapacity(8L * gib))
        assertEquals(96, KSLRuntimeSizing.recommendedCompileCacheEntries(8L * gib))

        assertEquals(6, KSLRuntimeSizing.recommendedWorkerCount(16L * gib, 16))
        assertEquals(512, KSLRuntimeSizing.recommendedQueueCapacity(16L * gib))
        assertEquals(128, KSLRuntimeSizing.recommendedCompileCacheEntries(16L * gib))
    }

    @Test
    fun `compile cache capacity shrinks as heap pressure increases`() {
        assertEquals(64, KSLRuntimeSizing.recommendedCompileCacheCapacity(64, 69, 100))
        assertEquals(48, KSLRuntimeSizing.recommendedCompileCacheCapacity(64, 72, 100))
        assertEquals(32, KSLRuntimeSizing.recommendedCompileCacheCapacity(64, 82, 100))
        assertEquals(16, KSLRuntimeSizing.recommendedCompileCacheCapacity(64, 90, 100))
        assertEquals(16, KSLRuntimeSizing.recommendedCompileCacheCapacity(32, 99, 100))
        assertEquals(32, KSLRuntimeSizing.recommendedCompileCacheCapacity(32, 99, -1))
    }

    @Test
    fun `smaller physical memory boundary controls automatic sizing`() {
        assertEquals(4L * gib, KSLRuntimeSizing.effectiveMemoryLimitBytes(8L * gib, 4L * gib))
        assertEquals(2, KSLRuntimeSizing.recommendedWorkerCount(8L * gib, 4L * gib, 16))
        assertEquals(128, KSLRuntimeSizing.recommendedQueueCapacity(8L * gib, 4L * gib))
        assertEquals(64, KSLRuntimeSizing.recommendedCompileCacheEntries(8L * gib, 4L * gib))
    }

    @Test
    fun `missing physical memory information falls back to the JVM heap`() {
        assertEquals(2, KSLRuntimeSizing.recommendedWorkerCount(4L * gib, -1L, 16))
        assertEquals(128, KSLRuntimeSizing.recommendedQueueCapacity(4L * gib, -1L))
        assertEquals(64, KSLRuntimeSizing.recommendedCompileCacheEntries(4L * gib, -1L))
    }

    @Test
    fun `processor count and unknown heap size never produce zero workers`() {
        assertEquals(1, KSLRuntimeSizing.recommendedWorkerCount(4L * gib, 1))
        assertEquals(1, KSLRuntimeSizing.recommendedWorkerCount(0L, 16))
        assertEquals(64, KSLRuntimeSizing.recommendedQueueCapacity(0L))
        assertEquals(32, KSLRuntimeSizing.recommendedCompileCacheEntries(0L))
    }
}
