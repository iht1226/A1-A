package dev.sleepalarm.analysis

import dev.sleepalarm.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AdaptiveWindowTest(private val minutes: Int) {
    @Test fun `all configured windows have causal warmup and a motion only decision before deadline`() {
        val start = AdaptiveFixture.START
        val end = start + minutes * 60_000L
        val gate = SustainedWakeGate()
        var fired = 0L
        for (at in start until end step 30_000) {
            val d = AdaptiveWakeEstimator().evaluate(at, start, end, AdaptiveFixture.motion(minutes), emptyList())
            if (gate.update(d.wake, at - start)) { fired = at; break }
        }
        assertTrue("$minutes minute window", fired in start + 240_000 until end)
        assertFalse(AdaptiveWakeEstimator().evaluate(end, start, end, AdaptiveFixture.motion(minutes), emptyList()).wake)
        assertFalse(AdaptiveWakeEstimator().evaluate(start - 1, start, end, AdaptiveFixture.motion(minutes), emptyList()).wake)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "adaptiveWindow={0}")
        fun windows(): List<Array<Any>> = (10..60).map { arrayOf<Any>(it) }
    }
}
