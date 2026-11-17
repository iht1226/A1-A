package dev.sleepalarm.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class VibrationPattern { GENTLE, PULSE, URGENT }

data class AlarmSettings(
    val hour: Int = 7,
    val minute: Int = 0,
    val windowMinutes: Int = 30,
    val smartEnabled: Boolean = true,
    val amplitude: Int = 160,
    val pattern: VibrationPattern = VibrationPattern.GENTLE,
    val snoozeMinutes: Int = 5,
) {
    init {
        require(hour in 0..23 && minute in 0..59)
        require(windowMinutes in 10..60)
        require(amplitude in 1..255)
        require(snoozeMinutes in 1..30)
    }

    fun nextDeadline(now: Instant, zone: ZoneId): Instant {
        val local = now.atZone(zone)
        // java.time resolves DST gaps forward and chooses the earlier offset in overlaps.
        var next = local.toLocalDate().atTime(LocalTime.of(hour, minute)).atZone(zone)
        if (!next.toInstant().isAfter(now)) next = next.plusDays(1)
        return next.toInstant()
    }
}

enum class RingKind { SMART, FINAL, SNOOZE }

data class AlarmPlan(
    val id: String,
    val armedAt: Long,
    val deadline: Long,
    val settings: AlarmSettings,
    val enabled: Boolean = true,
    val earlyDelivered: Boolean = false,
    val finalDelivered: Boolean = false,
    val snoozeAt: Long = 0,
    val ringNonce: String = "",
    val ringKind: RingKind? = null,
) {
    val windowStart: Long get() = deadline - settings.windowMinutes * 60_000L
    val ringing: Boolean get() = ringNonce.isNotEmpty()
}

/** These are observations, never inferred REM/light/deep sleep stages. */
data class MotionSample(val at: Long, val energy: Double, val coveredMillis: Long)
data class HeartSample(val at: Long, val bpm: Double)
data class WakeDecision(val wake: Boolean, val confidence: Double, val reason: String)

interface WakeEstimator {
    fun evaluate(now: Long, windowStart: Long, deadline: Long, motion: List<MotionSample>, heart: List<HeartSample>): WakeDecision
}

data class NightReport(
    val id: String,
    val armedAt: Long,
    val deadline: Long,
    val firstRingAt: Long = 0,
    val firstRingKind: String = "",
    val dismissedAt: Long = 0,
    val motionBins: Int = 0,
    val heartSamples: Int = 0,
    val decision: String = "관측 데이터 없음",
)

enum class HistoricalStage { UNKNOWN, AWAKE, SLEEPING, LIGHT, DEEP, REM, OUT_OF_BED }
data class StageInterval(val start: Instant, val end: Instant, val stage: HistoricalStage)
data class SleepHistory(
    val id: String,
    val start: Instant,
    val end: Instant,
    val source: String,
    val stages: List<StageInterval>,
)

/** Future personalization consumes post-wake history, without becoming an alarm dependency. */
interface PersonalizationProvider {
    fun parameters(history: List<SleepHistory>): Map<String, Double>
}
