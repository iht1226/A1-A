package dev.sleepalarm.analysis

import dev.sleepalarm.model.*
import org.junit.Assert.*
import org.junit.Test

object AdaptiveFixture {
    const val START = 6_000_000L
    fun motion(window: Int = 30, energy: Double = 0.06, quiet: Double = 0.005): List<MotionSample> {
        val from = START - SensorSchedule.warmupMillis(window)
        return (from until START + window * 60_000L step 5_000).map { at ->
            val active = at >= START && ((at - START) / 5_000 % 12).toInt() in listOf(2,3,4,8,9,10)
            MotionSample(at, if (active) energy else quiet, 4_600)
        }
    }
    fun heart(window: Int = 30, rise: Double = 6.0): List<HeartSample> =
        (START - SensorSchedule.warmupMillis(window)..START + window * 60_000L step 30_000).map {
            HeartSample(it, if (it < START) 60.0 else 60.0 + rise)
        }
}

class AdaptiveWakeEstimatorTest {
    private val start = AdaptiveFixture.START
    private val now = start + 180_000
    private val end = start + 30 * 60_000
    private fun evaluate(m: List<MotionSample> = AdaptiveFixture.motion(), h: List<HeartSample> = emptyList(), at: Long = now) =
        AdaptiveWakeEstimator().evaluate(at, start, end, m, h)

    @Test fun `sustained motion can wake without heart rate`() {
        val d = evaluate()
        assertTrue(d.wake); assertEquals("MOTION_ONLY", d.evidence!!.fusion)
        assertTrue(d.reason.contains("수면 단계 아님"))
    }
    @Test fun `flat heart rate never vetoes strong motion`() {
        assertTrue(evaluate(h = AdaptiveFixture.heart(rise = 0.0)).wake)
    }
    @Test fun `low quality stale or missing heart falls back to motion`() {
        assertTrue(evaluate(h = AdaptiveFixture.heart().map { it.copy(quality = 0.1) }).wake)
        val d = evaluate(h = AdaptiveFixture.heart().filter { it.at < now - 100_000 })
        assertTrue(d.wake); assertEquals(0.0, d.evidence!!.heartQuality, 0.0)
    }
    @Test fun `reliable HR corroborates ambiguous intensity without fixed BPM gate`() {
        assertFalse(evaluate(m = AdaptiveFixture.motion(energy = 0.018)).wake)
        val d = evaluate(m = AdaptiveFixture.motion(energy = 0.018), h = AdaptiveFixture.heart())
        assertTrue(d.wake); assertEquals("MOTION_HR_CORROBORATED", d.evidence!!.fusion)
    }
    @Test fun `heart change alone cannot wake`() {
        assertFalse(evaluate(m = AdaptiveFixture.motion(energy = 0.005), h = AdaptiveFixture.heart(rise = 30.0)).wake)
    }
    @Test fun `single turn or impulse is not repeated evidence`() {
        val quiet = AdaptiveFixture.motion(energy = 0.005)
        assertFalse(evaluate(m = quiet.map { if (it.at in start..start + 15_000) it.copy(energy = 0.2) else it }).wake)
        assertFalse(evaluate(m = quiet.map { if (it.at == now - 10_000) it.copy(energy = 2.0) else it }).wake)
    }
    @Test fun `missing or duplicated coverage does not masquerade as stillness or confidence`() {
        val sparse = AdaptiveFixture.motion().filterIndexed { i,_ -> i % 3 == 0 }
        assertFalse(evaluate(m = sparse + sparse + sparse).wake)
        assertFalse(evaluate(m = AdaptiveFixture.motion().map { it.copy(coveredMillis = 500) }).wake)
    }
    @Test fun `nonfinite negative invalid and future readings are rejected`() {
        assertFalse(evaluate(m = AdaptiveFixture.motion().map { it.copy(energy = Double.NaN) }).wake)
        assertFalse(evaluate(m = AdaptiveFixture.motion().map { it.copy(energy = -1.0) }).wake)
        assertFalse(evaluate(m = AdaptiveFixture.motion().map { it.copy(receivedAt = now + 60_000) }).wake)
        assertFalse(evaluate(m = AdaptiveFixture.motion().map { it.copy(coveredMillis = 6_000) }).wake)
    }
    @Test fun `restless individual baseline is different from fixed absolute motion cutoff`() {
        assertFalse(evaluate(m = AdaptiveFixture.motion(energy = 0.06, quiet = 0.08)).wake)
    }
    @Test fun `no valid personal warmup means no early wake`() {
        assertFalse(evaluate(m = AdaptiveFixture.motion().filter { it.at >= start }).wake)
    }
    @Test fun `threshold gets stricter with time and time never triggers motionless wake`() {
        val e = AdaptiveWakeEstimator()
        val thresholds = (0..30).map { e.requiredEvidence(start + it * 60_000, start, end) }
        assertTrue(thresholds.zipWithNext().all { (a,b) -> b >= a })
        assertFalse(evaluate(m = AdaptiveFixture.motion(energy = 0.005), at = end - 1).wake)
    }
    @Test fun `short sleep context adds cost only when sufficiently recent and complete`() {
        val c = SleepContext(300.0, 5, start - 3_600_000, start - 1000)
        val base = AdaptiveWakeEstimator().requiredEvidence(now, start, end)
        assertTrue(AdaptiveWakeEstimator(reference = PersonalReference(sleepContext = c)).requiredEvidence(now, start, end) > base)
        assertEquals(base, AdaptiveWakeEstimator(reference = PersonalReference(sleepContext = c.copy(recordedAt = now + 1))).requiredEvidence(now, start, end), 0.0)
    }
    @Test fun `future delayed HR is not available to causal replay`() {
        val comparison = CausalComparison(AdaptiveWakeEstimator())
        val r = comparison.update(now, 0, start, end, AdaptiveFixture.motion(energy = 0.018),
            AdaptiveFixture.heart().map { it.copy(receivedAt = now + 60_000) })
        assertFalse(r.adaptive.wake); assertEquals(0.0, r.adaptive.evidence!!.heartQuality, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            comparison.update(now + 1, 1, start, end, emptyList(), emptyList(), observedUntil = now)
        }
    }
    @Test fun `comparison is censored at actual observation end and does not prove efficacy`() {
        val comparison = CausalComparison(AdaptiveWakeEstimator())
        var last: ComparisonSnapshot? = null
        for (at in start + 180_000..start + 240_000 step 30_000)
            last = comparison.update(at, at - start, start, end, AdaptiveFixture.motion(), emptyList(), start + 240_000)
        assertEquals(start + 240_000, last!!.adaptiveAt)
        assertEquals(0L, last.legacyAt)
    }
}
