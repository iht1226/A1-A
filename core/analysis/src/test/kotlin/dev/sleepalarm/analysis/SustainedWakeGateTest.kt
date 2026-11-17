package dev.sleepalarm.analysis

import org.junit.Assert.*
import org.junit.Test

class SustainedWakeGateTest {
    @Test fun `exactly sixty seconds of positive evaluations confirms`() {
        val gate = SustainedWakeGate()
        assertFalse(gate.update(true, 0))
        assertFalse(gate.update(true, 30_000))
        assertFalse(gate.update(true, 59_999))
        assertTrue(gate.update(true, 60_000))
    }
    @Test fun `negative evidence resets confirmation`() {
        val gate = SustainedWakeGate()
        gate.update(true, 0); gate.update(false, 30_000)
        assertFalse(gate.update(true, 60_000))
        assertFalse(gate.update(true, 90_000))
        assertTrue(gate.update(true, 120_000))
    }
    @Test fun `suspended polling cannot count unobserved time as continuous`() {
        val gate = SustainedWakeGate()
        gate.update(true, 0)
        assertFalse(gate.update(true, 60_000))
        assertFalse(gate.update(true, 90_000))
        assertTrue(gate.update(true, 120_000))
    }
    @Test fun `clock regression starts a fresh confirmation`() {
        val gate = SustainedWakeGate()
        gate.update(true, 30_000)
        assertFalse(gate.update(true, 0))
        assertFalse(gate.update(true, 30_000))
        assertTrue(gate.update(true, 60_000))
    }
}
