package ru.privateserver.ksl.rt.monitoring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KSLQueuePressureMonitorTest {
    @Test
    fun doesNotWarnForStableOrSmallQueues() {
        val monitor = KSLQueuePressureMonitor()
        assertNull(monitor.sample(100, 80))
        assertNull(monitor.sample(120, 90))
        assertNull(monitor.sample(250, 160))
    }

    @Test
    fun warnsEarlyWhenAQueueGrowsRapidlyBeforeTheHighWatermark() {
        val monitor = KSLQueuePressureMonitor()
        assertNull(monitor.sample(1_000, 500))

        val event = monitor.sample(2_000, 600)

        assertEquals(KSLQueuePressureMonitor.Reason.RAPID_GROWTH, event?.reason)
        assertEquals(1_000, event?.writeGrowth)
        assertEquals(2_000, event?.writes)
    }

    @Test
    fun warnsAtTheHighWatermarkEvenWithoutRecentGrowth() {
        val monitor = KSLQueuePressureMonitor()
        assertNull(monitor.sample(4_900, 0))
        val event = monitor.sample(5_100, 0)

        assertEquals(KSLQueuePressureMonitor.Reason.HIGH_WATERMARK, event?.reason)
    }

    @Test
    fun emitsOnlyOneWarningUntilBothQueuesRecover() {
        val monitor = KSLQueuePressureMonitor()
        assertNull(monitor.sample(0, 0))
        assertEquals(KSLQueuePressureMonitor.Reason.HIGH_WATERMARK, monitor.sample(5_000, 0)?.reason)
        assertNull(monitor.sample(7_000, 0))
        assertNull(monitor.sample(900, 1_100))
        assertNull(monitor.sample(800, 900))
        assertEquals(KSLQueuePressureMonitor.Reason.HIGH_WATERMARK, monitor.sample(5_000, 0)?.reason)
    }

    @Test
    fun readerQueueCanTriggerAnEarlyWarningIndependently() {
        val monitor = KSLQueuePressureMonitor()
        assertNull(monitor.sample(0, 1_500))
        val event = monitor.sample(0, 2_100)
        assertEquals(KSLQueuePressureMonitor.Reason.RAPID_GROWTH, event?.reason)
        assertEquals(600, event?.readGrowth)
    }

    @Test
    fun rejectsInvalidQueueSizes() {
        val monitor = KSLQueuePressureMonitor()
        assertThrows(IllegalArgumentException::class.java) { monitor.sample(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { monitor.sample(0, -1) }
    }
}
