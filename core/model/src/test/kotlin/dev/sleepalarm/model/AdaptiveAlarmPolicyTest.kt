package dev.sleepalarm.model

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AdaptiveAlarmPolicyTest {
    private val deadline = 9_000_000L
    private fun plan(keep: Boolean = false) = AlarmPlan("n", 1, deadline,
        AlarmSettings(keepFinalAfterSmartDismiss = keep))
    @Test fun `default schedule collects 45 minutes before deadline and judges 30 minutes before`() {
        val p = plan()
        assertEquals(deadline - 45 * 60_000L, p.sensorStart)
        assertEquals(deadline - 30 * 60_000L, p.windowStart)
        for (minutes in 10..60) {
            val custom = p.copy(settings = p.settings.copy(windowMinutes = minutes))
            assertTrue(custom.sensorStart < custom.windowStart)
            assertTrue(custom.windowStart < custom.deadline)
        }
    }
    @Test fun `unanswered early ring retains final and acknowledgement cancels by default`() {
        val early = AlarmTransitions.smart(plan(), "n", deadline - 120_000, "e")!!
        assertFalse(early.finalDelivered)
        assertNotNull(AlarmTransitions.final(early, "n", deadline, deadline, "f"))
        val dismissed = AlarmTransitions.dismiss(early, "e")!!
        assertFalse(dismissed.enabled)
        assertNull(AlarmTransitions.final(dismissed, "n", deadline, deadline, "f"))
    }
    @Test fun `optional backup survives early acknowledgement but explicit cancel always cancels`() {
        val early = AlarmTransitions.smart(plan(true), "n", deadline - 120_000, "e")!!
        val backup = AlarmTransitions.dismiss(early, "e")!!
        assertTrue(backup.enabled); assertFalse(backup.ringing); assertTrue(backup.earlyDelivered)
        assertNotNull(AlarmTransitions.final(backup, "n", deadline, deadline, "f"))
        assertNull(AlarmTransitions.smart(backup, "n", deadline - 60_000, "e2"))
        assertFalse(AlarmTransitions.dismiss(backup, null)!!.enabled)
    }
    @Test fun `early snooze before and across deadline never overshoots final`() {
        val early = AlarmTransitions.smart(plan(), "n", deadline - 600_000, "e")!!
        assertEquals(deadline - 300_000, AlarmTransitions.snooze(early, "e", deadline - 600_000)!!.snoozeAt)
        assertEquals(deadline, AlarmTransitions.snooze(early, "e", deadline - 30_000)!!.snoozeAt)
    }
    @Test fun `backup option does not repeat a dismissed final alarm`() {
        val final = AlarmTransitions.final(plan(true), "n", deadline, deadline, "f")!!
        assertFalse(AlarmTransitions.dismiss(final, "f")!!.enabled)
        assertTrue(AlarmTransitions.snooze(final, "f", deadline)!!.snoozeAt > deadline)
    }
    @Test fun `assignment modes are explicit and deterministic given the random draw`() {
        assertEquals(AlgorithmId.ADAPTIVE_020, TrialAssignment.choose(AlgorithmMode.ADAPTIVE, 1, false))
        assertEquals(AlgorithmId.LEGACY_011, TrialAssignment.choose(AlgorithmMode.LEGACY, 0, true))
        assertEquals(AlgorithmId.ADAPTIVE_020, TrialAssignment.choose(AlgorithmMode.ALTERNATE, 0, false))
        assertEquals(AlgorithmId.LEGACY_011, TrialAssignment.choose(AlgorithmMode.ALTERNATE, 1, false))
        assertEquals(AlgorithmId.ADAPTIVE_020, TrialAssignment.choose(AlgorithmMode.RANDOM, 0, true))
        assertEquals(AlgorithmId.LEGACY_011, TrialAssignment.choose(AlgorithmMode.RANDOM, 0, false))
    }
    @Test fun `incomplete stages are not invented total sleep time`() {
        val start = Instant.parse("2026-11-16T22:00:00Z"); val end = start.plusSeconds(8 * 3600)
        val empty = SleepHistory("id", start, end, "Samsung", emptyList())
        assertNull(SleepSummaries.knownSleepMinutes(empty))
        val complete = empty.copy(stages = listOf(StageInterval(start, end, HistoricalStage.LIGHT)))
        assertEquals(480L, SleepSummaries.knownSleepMinutes(complete))
        assertNull(SleepSummaries.knownSleepMinutes(complete.copy(stages = complete.stages + complete.stages)))
        assertNull(SleepSummaries.knownSleepMinutes(empty.copy(stages = listOf(StageInterval(start, end, HistoricalStage.UNKNOWN)))))
    }
}
