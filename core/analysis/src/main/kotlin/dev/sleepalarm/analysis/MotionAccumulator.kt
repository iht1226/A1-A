package dev.sleepalarm.analysis

import dev.sleepalarm.model.MotionSample
import kotlin.math.sqrt

/** Integrates only actually observed <=1s intervals, splitting them at five-second boundaries. */
class MotionAccumulator {
    private var previousAt: Long? = null
    private var previousEnergy = 0.0
    private var bucket = Long.MIN_VALUE
    private var weightedSquare = 0.0
    private var covered = 0L
    var lastInterval: Pair<Long, Long>? = null
        private set

    fun add(at: Long, energy: Double, receivedAt: Long = at + 5_000): MotionSample? {
        lastInterval = null
        if (!energy.isFinite() || energy < 0 || previousAt?.let { at <= it } == true) return null
        val previous = previousAt
        val nextBucket = at / 5_000 * 5_000
        var completed: MotionSample? = null
        if (bucket == Long.MIN_VALUE) bucket = nextBucket
        if (previous != null && at - previous <= 1_000) {
            lastInterval = previous to at
            val leftEnd = minOf(at, bucket + 5_000)
            if (previous < leftEnd) integrate(leftEnd - previous)
        }
        if (nextBucket != bucket) {
            completed = sample(receivedAt)
            bucket = nextBucket; weightedSquare = 0.0; covered = 0
            if (previous != null && at - previous <= 1_000 && at > bucket) integrate(at - bucket)
        }
        previousAt = at; previousEnergy = energy
        return completed
    }
    private fun integrate(dt: Long) { covered += dt; weightedSquare += previousEnergy * previousEnergy * dt }
    private fun sample(received: Long): MotionSample? = if (covered > 0)
        MotionSample(bucket, sqrt(weightedSquare / covered), covered, received) else null
    /** Never extends the last value to shutdown time or fills an unobserved gap. */
    fun finish(receivedAt: Long): MotionSample? = sample(receivedAt).also {
        bucket = Long.MIN_VALUE; covered = 0; weightedSquare = 0.0; previousAt = null; lastInterval = null
    }
}
