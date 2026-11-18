package dev.sleepalarm.sensors

import dev.sleepalarm.model.MotionSample
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenCoverageTest {
    @Test fun `a late FIFO batch counts by measurement time and losses stay in the off denominator`() {
        val transitions = JSONArray()
        listOf(0L to "ON", 10_000L to "OFF", 45_000L to "DOZE", 50_000L to "ON").forEach { (at, state) ->
            transitions.put(JSONObject().put("at", at).put("state", state))
        }
        val spans = listOf(0L to 15_000L, 40_000L to 45_000L, 50_000L to 60_000L)
        val motion = listOf(0L, 5_000, 10_000, 40_000, 50_000, 55_000).map { MotionSample(it, 0.02, 5_000, 70_000) }
        val coverage = CaptureEnvironment.displayCoverage(transitions, 0, 60_000, spans, motion)
        val off = coverage.getJSONObject("OFF")
        assertEquals(35_000L, off.getLong("duration"))
        assertEquals(10_000L, off.getLong("observed"))
        assertEquals(10_000L, off.getLong("stored"))
        assertEquals(25_000L, off.getLong("gap"))
        assertEquals(20_000L, coverage.getJSONObject("ON").getLong("observed"))
        assertEquals(0L, coverage.getJSONObject("DOZE").getLong("observed"))
    }
}
