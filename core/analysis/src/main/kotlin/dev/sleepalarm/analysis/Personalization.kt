package dev.sleepalarm.analysis

import dev.sleepalarm.model.*

object Personalization {
    fun reference(reports: List<NightReport>, before: Long, context: SleepContext): PersonalReference {
        val past = reports.filter { it.armedAt < before && it.firstRingAt in 1 until before && it.dismissedAt in 1 until before &&
            it.details.smartScheduled && !it.details.testAlarm &&
            it.details.motionQuality >= 0.8 }.sortedBy { it.armedAt }.takeLast(30)
        val feedback = past.filter { it.details.algorithm == AlgorithmId.ADAPTIVE_020 &&
            it.details.feedbackAt in 1 until before && it.details.refreshed in 1..5 && it.details.sleepiness in 1..5 }
        val offset = if (feedback.size < 8) 0.0 else
            (0.04 * feedback.map { (it.details.sleepiness - it.details.refreshed) / 4.0 }.average()).coerceIn(-0.04, 0.04)
        return PersonalReference(past.map { it.details.referenceMedian }, past.map { it.details.referenceMad },
            offset, feedback.size, context)
    }
}

data class ComparisonSnapshot(val legacy: WakeDecision, val adaptive: WakeDecision, val legacyAt: Long, val adaptiveAt: Long)

/** Streaming, causal replay: never reads beyond the provided observed cutoff. */
class CausalComparison(private val adaptive: WakeEstimator, private val legacy: WakeEstimator = ConservativeWakeEstimator()) {
    private val legacyGate = SustainedWakeGate()
    private val adaptiveGate = SustainedWakeGate()
    private var legacyAt = 0L
    private var adaptiveAt = 0L
    private var legacyChosen: WakeDecision? = null
    private var adaptiveChosen: WakeDecision? = null
    fun update(now: Long, elapsed: Long, windowStart: Long, deadline: Long, motion: List<MotionSample>, heart: List<HeartSample>, observedUntil: Long = now): ComparisonSnapshot {
        require(now <= observedUntil) { "Cannot replay an unobserved future" }
        val m = motion.filter { it.at + 5_000 <= now && it.at + 5_000 <= observedUntil && it.receivedAt <= now }
        val h = heart.filter { it.at <= now && it.at <= observedUntil && it.receivedAt <= now }
        val old = legacy.evaluate(now, windowStart, deadline, m, h.filter { it.quality >= 0.7 })
        val new = adaptive.evaluate(now, windowStart, deadline, m, h)
        if (legacyAt == 0L && legacyGate.update(old.wake, elapsed)) { legacyAt = now; legacyChosen = old }
        if (adaptiveAt == 0L && adaptiveGate.update(new.wake, elapsed)) { adaptiveAt = now; adaptiveChosen = new }
        return ComparisonSnapshot(legacyChosen ?: old, adaptiveChosen ?: new, legacyAt, adaptiveAt)
    }
}
