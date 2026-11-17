package dev.sleepalarm.analysis

import dev.sleepalarm.model.*
import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    private fun report(i: Int, refreshed: Int = 1, sleepy: Int = 5) = NightReport("$i", i * 1000L, i * 1000L + 500,
        firstRingAt = i * 1000L + 500, dismissedAt = i * 1000L + 600,
        details = NightDetails(algorithm = AlgorithmId.ADAPTIVE_020, smartScheduled = true,
            motionQuality = 0.9, referenceMedian = 0.005, referenceMad = 0.002,
            refreshed = refreshed, sleepiness = sleepy, feedbackAt = i * 1000L + 700))
    @Test fun `not enough eligible feedback keeps default threshold`() {
        val r = Personalization.reference((1..7).map { report(it) }, 100_000, SleepContext())
        assertEquals(0.0, r.thresholdOffset, 0.0); assertEquals(7, r.feedbackNights)
    }
    @Test fun `eight eligible nights adjust within a bounded range`() {
        val bad = Personalization.reference((1..8).map { report(it) }, 100_000, SleepContext())
        val good = Personalization.reference((1..8).map { report(it, 5, 1) }, 100_000, SleepContext())
        assertEquals(0.04, bad.thresholdOffset, 0.0); assertEquals(-0.04, good.thresholdOffset, 0.0)
    }
    @Test fun `future feedback test alarms and poor quality cannot train past decisions`() {
        val future = (1..8).map { report(it).let { r -> r.copy(details = r.details.copy(feedbackAt = 200_000)) } }
        assertEquals(0, Personalization.reference(future, 100_000, SleepContext()).feedbackNights)
        val tests = (1..8).map { report(it).let { r -> r.copy(details = r.details.copy(testAlarm = true)) } }
        assertEquals(0, Personalization.reference(tests, 100_000, SleepContext()).feedbackNights)
        val bad = (1..8).map { report(it).let { r -> r.copy(details = r.details.copy(motionQuality = 0.1)) } }
        assertTrue(Personalization.reference(bad, 100_000, SleepContext()).motionMedians.isEmpty())
    }
    @Test fun `invalid adaptive parameters fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { AdaptiveParameters(minCoverage = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { AdaptiveParameters(nearDeadlineTightening = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { AdaptiveWakeEstimator(userThreshold = Double.NaN) }
    }
}
