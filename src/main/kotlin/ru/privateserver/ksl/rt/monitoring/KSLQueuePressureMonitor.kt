package ru.privateserver.ksl.rt.monitoring

/**
 * Detects sustained queue pressure and rapid queue growth before the hard queue limit is reached.
 *
 * Sampling is intentionally cheap: it only compares executor queue sizes, never performs database I/O.
 * A warning is emitted once per pressure episode and is re-armed only after both queues recover.
 */
internal class KSLQueuePressureMonitor(
    private val warningAt: Int = 5_000,
    private val earlyWarningAt: Int = 2_000,
    private val growthThreshold: Int = 500,
    private val recoverBelow: Int = 1_000
) {
    enum class Reason { HIGH_WATERMARK, RAPID_GROWTH }

    data class Event(
        val writes: Int,
        val reads: Int,
        val writeGrowth: Int,
        val readGrowth: Int,
        val reason: Reason
    )

    private var previousWrites = 0
    private var previousReads = 0
    private var hasPreviousSample = false
    private var warningActive = false

    init {
        require(recoverBelow >= 0)
        require(earlyWarningAt > recoverBelow)
        require(warningAt > earlyWarningAt)
        require(growthThreshold > 0)
    }

    @Synchronized
    fun sample(writes: Int, reads: Int): Event? {
        require(writes >= 0) { "writes must not be negative" }
        require(reads >= 0) { "reads must not be negative" }

        val writeGrowth = if (hasPreviousSample) writes - previousWrites else 0
        val readGrowth = if (hasPreviousSample) reads - previousReads else 0
        val highWatermark = writes >= warningAt || reads >= warningAt
        val rapidlyGrowing =
            (writes >= earlyWarningAt && writeGrowth >= growthThreshold) ||
            (reads >= earlyWarningAt && readGrowth >= growthThreshold)

        previousWrites = writes
        previousReads = reads
        hasPreviousSample = true

        if (warningActive) {
            if (writes <= recoverBelow && reads <= recoverBelow) warningActive = false
            return null
        }

        if (!highWatermark && !rapidlyGrowing) return null

        warningActive = true
        return Event(
            writes = writes,
            reads = reads,
            writeGrowth = writeGrowth,
            readGrowth = readGrowth,
            reason = if (highWatermark) Reason.HIGH_WATERMARK else Reason.RAPID_GROWTH
        )
    }
}
