package dev.sleepalarm.analysis

import dev.sleepalarm.model.*
import org.junit.Assert.*
import org.junit.Test

class CaptureDiagnosticsTest {
    @Test fun `five Hz coverage crosses boundaries without losing observed time`() {
        val a = MotionAccumulator(); val bins = mutableListOf<MotionSample>()
        for (at in 0L..60_000L step 200) a.add(at, 0.03, at + 10_000)?.let { bins += it }
        a.finish(70_000)?.let { bins += it }
        assertEquals(12, bins.size); assertEquals(60_000L, bins.sumOf { it.coveredMillis })
        assertTrue(bins.all { it.coveredMillis == 5_000L && kotlin.math.abs(it.energy - 0.03) < 1e-9 })
        assertTrue(bins.all { it.receivedAt >= it.at + 5_000 })
    }
    @Test fun `irregular event intervals preserve exact known time and never bridge long gaps`() {
        val a = MotionAccumulator(); val spans = mutableListOf<Pair<Long,Long>>(); val bins = mutableListOf<MotionSample>()
        listOf(0L,180L,450L,1000L,5000L,5200L,5900L,6100L).forEach { at ->
            a.add(at,0.02,at+60_000)?.let { bins += it }; a.lastInterval?.let { spans += it }
        }
        a.finish(70_000)?.let { bins += it }
        assertEquals(2100L, bins.sumOf { it.coveredMillis })
        val c = CoverageDiagnostics.measure(0,10_000,spans)
        assertEquals(2100L,c.observedMillis); assertEquals(4000L,c.maximumGapMillis)
        assertEquals(0.21,c.ratio,1e-9)
    }
    @Test fun `duplicate reversed invalid events do not inflate coverage or become stillness`() {
        val a = MotionAccumulator()
        a.add(1000,0.01); a.add(1200,0.01)
        assertNull(a.add(1200,0.02)); assertNull(a.add(1100,0.02)); assertNull(a.add(1300,Double.NaN))
        assertEquals(200L,a.finish(2000)!!.coveredMillis)
    }
    @Test fun `suspend delayed FIFO samples are retained but future and unreliable samples are rejected`() {
        assertNull(CapturePolicy.reject(100_000,250_000,1,300_000,100_000_000_000,0,0.01,3))
        assertEquals("FUTURE_TIMESTAMP",CapturePolicy.reject(251_000,250_000,1,300_000,251_000_000_000,0,0.01,3))
        assertEquals("UNRELIABLE_ACCURACY",CapturePolicy.reject(100_000,250_000,1,300_000,100_000_000_000,0,0.01,0))
        assertEquals("DUPLICATE_OR_REORDERED",CapturePolicy.reject(100_000,250_000,1,300_000,100,100,0.01,3))
    }
    @Test fun `FIFO sizing honors requested low sampling rate and available capacity`() {
        assertEquals(5_000_000,CapturePolicy.latencyUs(500,100))
        assertEquals(1_000_000,CapturePolicy.latencyUs(500,10))
        assertEquals(0,CapturePolicy.latencyUs(0,0))
        assertEquals(5_000_000,CapturePolicy.latencyUs(100,0))
    }
    @Test fun `coverage has precise leading trailing overlap gaps and minute denominators`() {
        val spans = listOf(10_000L to 40_000L,30_000L to 60_000L,90_000L to 110_000L)
        val c = CoverageDiagnostics.measure(0,120_000,spans)
        assertEquals(70_000L,c.observedMillis); assertEquals(30_000L,c.maximumGapMillis)
        val minutes = CoverageDiagnostics.minutes(0,120_000,spans)
        assertEquals(listOf(50_000L,20_000L),minutes.map { it.observedMillis })
    }
    @Test fun `ninety five bins over forty five minutes cannot masquerade as full observation`() {
        val spans = (0 until 95).map { it*25_000L to it*25_000L+5_000 }
        val c = CoverageDiagnostics.measure(0,45*60_000,spans)
        assertEquals(475_000,c.observedMillis)
        assertTrue(c.ratio < 0.18)
        val bins = spans.map { MotionSample(it.first,0.04,5000, it.second) }
        val heart = (0..2400).map { HeartSample(it*1000L,65.0,1.0,it*1000L) }
        val d = AdaptiveWakeEstimator().evaluate(1_200_000,900_000,2_700_000,bins,heart)
        assertFalse(d.wake); assertTrue(d.reason.contains("기준선"))
    }
    @Test fun `adequate stillness is different from insufficient observation`() {
        val motion = (0L until 1_200_000 step 5000).map { MotionSample(it,0.005,5000,it+5000) }
        val d = AdaptiveWakeEstimator().evaluate(1_200_000,900_000,2_700_000,motion,emptyList())
        assertFalse(d.wake); assertNotNull(d.evidence); assertTrue(d.evidence!!.motionQuality >= 0.99)
        assertTrue(d.reason.contains("미달"))
    }
    @Test fun `batch arrival cannot be used before receipt even with dense heart data`() {
        val all = (0L until 1_400_000 step 5000).map { MotionSample(it,if (it < 900_000) 0.005 else 0.06,5000,1_300_000) }
        val a = AdaptiveWakeEstimator()
        assertFalse(a.evaluate(1_200_000,900_000,2_700_000,all,emptyList()).wake)
        assertTrue(a.evaluate(1_350_000,900_000,2_700_000,all,emptyList()).wake)
    }
    @Test fun `replay uses recorded ticks generations arrival times and exact input snapshots`() {
        val calls = mutableListOf<List<Long>>()
        val ticks = listOf(ReplayTick(6000,6000,1,true,setOf(0)),ReplayTick(7000,7000,2,false,emptySet()))
        val result = OfflineReplay.run(ticks,0,10_000,7000,listOf(MotionSample(0,0.01,1,10)),emptyList()) {
            object : WakeEstimator { override fun evaluate(now:Long,windowStart:Long,deadline:Long,motion:List<MotionSample>,heart:List<HeartSample>):WakeDecision {
                calls += motion.map { it.at }; return WakeDecision(false,0.0,"observed")
            } }
        }
        assertEquals(2,result.size)
        assertEquals(listOf(listOf(0L),emptyList<Long>()),calls) // later DB rows cannot contaminate the saved evaluation snapshot
        assertThrows(IllegalArgumentException::class.java) { OfflineReplay.run(listOf(ReplayTick(7001,7001,1,true)),0,10000,7000,emptyList(),emptyList()) { AdaptiveWakeEstimator() } }
    }
}
