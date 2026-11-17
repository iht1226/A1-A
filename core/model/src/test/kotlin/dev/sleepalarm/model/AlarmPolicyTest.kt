package dev.sleepalarm.model

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AlarmPolicyTest {
    private val deadline = 2_000_000L
    private fun plan() = AlarmPlan("night", 1, deadline, AlarmSettings())

    @Test fun `early ring preserves independent final deadline and unacknowledged final fires`() {
        val smart = AlarmTransitions.smart(plan(), "night", deadline - 60_000, "early")!!
        assertEquals(deadline, smart.deadline)
        assertFalse(smart.finalDelivered)
        val final = AlarmTransitions.final(smart, "night", deadline, deadline, "final")!!
        assertEquals(RingKind.FINAL, final.ringKind)
        assertTrue(final.finalDelivered)
    }
    @Test fun `early snooze is capped at deadline and final alarm stays eligible`() {
        val smart = AlarmTransitions.smart(plan(), "night", deadline - 60_000, "early")!!
        val snoozed = AlarmTransitions.snooze(smart, "early", deadline - 30_000)!!
        assertEquals(deadline, snoozed.snoozeAt)
        val final = AlarmTransitions.final(snoozed, "night", deadline, deadline, "final")!!
        assertEquals(0L, final.snoozeAt)
        assertNull(AlarmTransitions.snoozeDelivery(final, "night", snoozed.snoozeAt, snoozed.snoozeAt, "late"))
    }
    @Test fun `explicit dismiss cancels all future delivery`() {
        val smart = AlarmTransitions.smart(plan(), "night", deadline - 60_000, "early")!!
        val done = AlarmTransitions.dismiss(smart, "early")!!
        assertFalse(done.enabled)
        assertNull(AlarmTransitions.final(done, "night", deadline, deadline, "final"))
    }
    @Test fun `stale notification cannot dismiss or snooze a new ring`() {
        val final = AlarmTransitions.final(plan(), "night", deadline, deadline, "new")!!
        assertNull(AlarmTransitions.dismiss(final, "old"))
        assertNull(AlarmTransitions.snooze(final, "old", deadline))
    }
    @Test fun `wrong session and duplicate final events are ignored`() {
        assertNull(AlarmTransitions.final(plan(), "old", deadline, deadline, "n"))
        val final = AlarmTransitions.final(plan(), "night", deadline, deadline, "n")!!
        assertNull(AlarmTransitions.final(final, "night", deadline, deadline + 1, "repeat"))
    }
    @Test fun `smart ring obeys window and disabled setting`() {
        assertNull(AlarmTransitions.smart(plan(), "night", plan().windowStart - 1, "n"))
        assertNull(AlarmTransitions.smart(plan(), "night", deadline, "n"))
        assertNull(AlarmTransitions.smart(plan().copy(settings = AlarmSettings(smartEnabled = false)), "night", deadline - 1, "n"))
    }
    @Test fun `overdue final can recover and final snooze works`() {
        val final = AlarmTransitions.final(plan(), "night", deadline, deadline + 600_000, "n")!!
        val snooze = AlarmTransitions.snooze(final, "n", deadline + 600_000)!!
        assertNotNull(AlarmTransitions.snoozeDelivery(snooze, "night", snooze.snoozeAt, snooze.snoozeAt, "s"))
    }
    @Test fun `next deadline handles midnight exact minute and DST gap`() {
        val s = AlarmSettings(hour = 7)
        assertEquals(Instant.parse("2026-11-18T07:00:00Z"), s.nextDeadline(Instant.parse("2026-11-17T07:00:00Z"), ZoneOffset.UTC))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), AlarmSettings(hour = 2, minute = 30).nextDeadline(Instant.parse("2026-03-28T23:00:00Z"), ZoneId.of("Europe/Berlin")))
    }
    @Test fun `out of range settings are rejected`() {
        listOf<() -> AlarmSettings>({ AlarmSettings(windowMinutes = 9) }, { AlarmSettings(windowMinutes = 61) }, { AlarmSettings(amplitude = 0) }, { AlarmSettings(snoozeMinutes = 0) }, { AlarmSettings(hour = 24) }).forEach {
            assertThrows(IllegalArgumentException::class.java) { it() }
        }
    }
}
