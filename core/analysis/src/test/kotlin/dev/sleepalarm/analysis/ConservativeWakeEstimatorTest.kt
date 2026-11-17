package dev.sleepalarm.analysis

import dev.sleepalarm.model.*
import org.junit.Assert.*
import org.junit.Test

class ConservativeWakeEstimatorTest {
    private val now = 1_800_000L
    private val start = now - 20 * 60_000
    private val deadline = now + 10 * 60_000
    private val split = now - 3 * 60_000
    private fun motion() = (now - 9 * 60_000 until now step 5_000).map {
        MotionSample(it, if (it < split) 0.005 else 0.08, 4_600)
    }
    private fun heart() = (now - 9 * 60_000..now step 30_000).map {
        HeartSample(it, if (it < split) 58.0 else 65.0)
    }
    private fun evaluate(m: List<MotionSample> = motion(), h: List<HeartSample> = heart(), time: Long = now) =
        ConservativeWakeEstimator().evaluate(time, start, deadline, m, h)

    @Test fun `sustained joint evidence can recommend wake without claiming a sleep stage`() {
        val result = evaluate()
        assertTrue(result.wake)
        assertTrue(result.reason.contains("수면 단계 아님"))
    }
    @Test fun `no data or a missing modality never triggers early wake`() {
        assertFalse(evaluate(emptyList(), emptyList()).wake)
        assertFalse(evaluate(h = emptyList()).wake)
        assertFalse(evaluate(m = emptyList()).wake)
    }
    @Test fun `stale heart samples and future samples cannot trigger wake`() {
        assertFalse(evaluate(h = heart().filter { it.at < now - 150_000 }).wake)
        assertFalse(evaluate(h = heart().map { it.copy(at = it.at + 1_000_000) }).wake)
    }
    @Test fun `bursty callbacks and duplicates do not create coverage`() {
        val burst = listOf(HeartSample(now - 2_000, 58.0), HeartSample(now - 1_000, 65.0), HeartSample(now, 65.0))
        assertFalse(evaluate(h = burst).wake)
        val sparse = motion().filterIndexed { i, _ -> i % 4 == 0 }
        assertFalse(evaluate(m = sparse.flatMap { listOf(it, it, it, it) }).wake)
    }
    @Test fun `actual irregular timestamps are accepted with sufficient coverage`() {
        val irregular = heart().mapIndexed { i, p -> p.copy(at = p.at - (i % 3) * 1_200) }
        assertTrue(evaluate(h = irregular).wake)
    }
    @Test fun `motion alone and heart alone are insufficient`() {
        assertFalse(evaluate(h = heart().map { it.copy(bpm = 58.0) }).wake)
        assertFalse(evaluate(m = motion().map { it.copy(energy = 0.005) }).wake)
    }
    @Test fun `extreme or nonfinite observations are rejected`() {
        assertFalse(evaluate(h = heart().map { it.copy(bpm = Double.NaN) }).wake)
        assertFalse(evaluate(m = motion().map { it.copy(energy = Double.NaN) }).wake)
        assertFalse(evaluate(m = motion().map { if (it.at >= split) it.copy(energy = 2.0) else it }).wake)
        assertFalse(evaluate(h = heart().map { if (it.at >= split) it.copy(bpm = 100.0) else it }).wake)
    }
    @Test fun `outside window and insufficient baseline always hold`() {
        assertFalse(evaluate(time = start - 1).wake)
        assertFalse(evaluate(time = deadline).wake)
        assertFalse(evaluate(time = start + 8 * 60_000).wake)
    }
    @Test fun `accelerometer gaps do not count as stillness`() {
        val a = MotionAccumulator()
        a.add(100, 0.1); a.add(300, 0.1)
        val bin = a.add(5_100, 0.1)!!
        assertEquals(200L, bin.coveredMillis)
        assertEquals(0.1, bin.energy, 0.001)
        assertNull(a.add(5_000, 0.1))
        assertNull(a.add(5_300, Double.NaN))
    }
}
