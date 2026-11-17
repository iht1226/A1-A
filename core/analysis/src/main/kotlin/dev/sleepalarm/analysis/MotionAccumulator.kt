package dev.sleepalarm.analysis

import dev.sleepalarm.model.MotionSample
import kotlin.math.sqrt

/** Uses actual event timestamps and caps gaps; missing time is never counted as stillness. */
class MotionAccumulator {
    private var previousAt: Long? = null
    private var previousEnergy = 0.0
    private var bucket = Long.MIN_VALUE
    private var weightedSquare = 0.0
    private var covered = 0L

    fun add(at: Long, energy: Double): MotionSample? {
        if (!energy.isFinite() || energy < 0) return null
        val previous = previousAt
        if (previous != null && at <= previous) return null
        val nextBucket = (at / 5_000) * 5_000
        var completed: MotionSample? = null
        if (nextBucket != bucket) {
            if (bucket != Long.MIN_VALUE) completed = MotionSample(
                bucket, if (covered > 0) sqrt(weightedSquare / covered) else 0.0, covered,
            )
            bucket = nextBucket
            weightedSquare = 0.0
            covered = 0
        }
        if (previous != null && at - previous <= 1_000 && previous >= bucket) {
            val dt = at - previous
            covered += dt
            weightedSquare += previousEnergy * previousEnergy * dt
        }
        previousAt = at
        previousEnergy = energy
        return completed
    }
}
