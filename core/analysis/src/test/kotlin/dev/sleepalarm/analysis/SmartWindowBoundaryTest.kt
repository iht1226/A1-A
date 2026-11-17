package dev.sleepalarm.analysis

import dev.sleepalarm.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SmartWindowBoundaryTest(private val minutes: Int) {
    private val length = minutes * 60_000L
    private val timing = WakeTiming.forWindow(length)
    private val estimator = ConservativeWakeEstimator()

    private fun evidence(start: Long): Pair<List<MotionSample>, List<HeartSample>> {
        val riseAt = start + timing.baselineMillis
        // Real five-second bins with incomplete coverage, not one sample per theoretical tick.
        val firstBin = ((start + 4_999) / 5_000) * 5_000
        val motion = (firstBin until start + length step 5_000).map {
            MotionSample(it, if (it < riseAt) 0.005 else 0.08, 4_600)
        }
        val heart = (start + 15_000..start + length step 30_000).map {
            HeartSample(it, if (it < riseAt) 58.0 else 65.0)
        }
        return motion to heart
    }

    @Test fun `actual polling and sixty second confirmation can finish before every deadline`() {
        for (phase in listOf(0L, 1_234L, 4_999L)) {
            val start = 3_000_000L + phase
            val deadline = start + length
            val (motion, heart) = evidence(start)
            val gate = SustainedWakeGate()
            var firedAt: Long? = null
            // Include the service's worst-case 15-second registration wait and first 30-second delay.
            for (now in start + 45_000 until deadline step WakeTiming.EVALUATION_MILLIS) {
                val decision = estimator.evaluate(now, start, deadline, motion, heart)
                if (gate.update(decision.wake, now - start)) { firedAt = now; break }
            }
            assertNotNull("${minutes}min phase=$phase must have an early decision opportunity", firedAt)
            assertTrue(firedAt!! < deadline)
            assertTrue(firedAt - start >= timing.observationMillis + WakeTiming.CONFIRMATION_MILLIS)
            if (minutes == 10) assertTrue("short window must retain useful margin", deadline - firedAt >= 120_000)
        }
    }

    @Test fun `warmup boundaries and final deadline are respected in every window`() {
        val start = 3_000_000L
        val end = start + length
        val (motion, heart) = evidence(start)
        fun at(now: Long) = estimator.evaluate(now, start, end, motion, heart)
        assertFalse(at(start - 1).wake)
        assertFalse(at(start).wake)
        assertFalse(at(start + timing.observationMillis - 1).wake)
        assertTrue(at(start + timing.observationMillis).wake)
        assertFalse(at(end).wake)
        assertFalse(at(end + 1).wake)
        val gate = SustainedWakeGate()
        // A late first observation cannot borrow confirmation time from after the final deadline.
        assertFalse(gate.update(at(end - 30_000).wake, length - 30_000))
        assertFalse(gate.update(at(end).wake, length))
    }

    @Test fun `shorter warmup never bypasses missing data gates`() {
        val start = 3_000_000L
        val end = start + length
        val (motion, heart) = evidence(start)
        val now = start + timing.observationMillis
        assertFalse(estimator.evaluate(now, start, end, motion, emptyList()).wake)
        assertFalse(estimator.evaluate(now, start, end, emptyList(), heart).wake)
        assertFalse(estimator.evaluate(now, start, end, motion, heart.filter { it.at < now - 150_000 }).wake)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "window={0}min")
        fun windows(): List<Array<Any>> = (10..60).map { arrayOf<Any>(it) }
    }
}
