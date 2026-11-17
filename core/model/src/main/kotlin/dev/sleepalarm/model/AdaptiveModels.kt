package dev.sleepalarm.model

enum class AlgorithmId { LEGACY_011, ADAPTIVE_020 }
enum class AlgorithmMode { ADAPTIVE, LEGACY, ALTERNATE, RANDOM }

object SensorSchedule {
    fun warmupMillis(windowMinutes: Int): Long {
        require(windowMinutes in 10..60)
        return (windowMinutes / 2).coerceIn(6, 15) * 60_000L
    }
}

data class WakeEvidence(
    val motionQuality: Double = 0.0,
    val heartQuality: Double = 0.0,
    val motionMedian: Double = 0.0,
    val motionMad: Double = 0.0,
    val heartMedian: Double = 0.0,
    val heartMad: Double = 0.0,
    val intensity: Double = 0.0,
    val frequency: Double = 0.0,
    val duration: Double = 0.0,
    val trend: Double = 0.0,
    val required: Double = 1.0,
    val fusion: String = "INSUFFICIENT",
)

data class SleepContext(val meanMinutes: Double = 0.0, val nights: Int = 0, val lastSleepEnd: Long = 0, val recordedAt: Long = 0) {
    fun usable(now: Long) = meanMinutes.isFinite() && meanMinutes in 30.0..900.0 && nights in 2..30 &&
        recordedAt in 1..now && lastSleepEnd in (now - 3 * 86_400_000L)..now
}

data class AlarmEvent(val at: Long, val kind: String, val action: String)

data class NightDetails(
    val algorithm: AlgorithmId = AlgorithmId.LEGACY_011,
    val mode: AlgorithmMode = AlgorithmMode.LEGACY,
    val smartScheduled: Boolean = false,
    val testAlarm: Boolean = false,
    val observationEnd: Long = 0,
    val legacySuggestedAt: Long = 0,
    val adaptiveSuggestedAt: Long = 0,
    val legacyReason: String = "평가 전",
    val adaptiveReason: String = "평가 전",
    val legacyConfidence: Double = 0.0,
    val adaptiveConfidence: Double = 0.0,
    val motionQuality: Double = 0.0,
    val heartQuality: Double = 0.0,
    val referenceMedian: Double = 0.0,
    val referenceMad: Double = 0.0,
    val referenceHeart: Double = 0.0,
    val referenceHeartMad: Double = 0.0,
    val personalizationOffset: Double = 0.0,
    val personalizedNights: Int = 0,
    val batteryStart: Int = -1,
    val batteryEnd: Int = -1,
    val batteryStartedAt: Long = 0,
    val outputStartedAt: Long = 0,
    val firstDismissedAt: Long = 0,
    val refreshed: Int = 0,
    val sleepiness: Int = 0,
    val feedbackAt: Long = 0,
    val healthSleepMinutes: Long = -1,
    val healthSleepEnd: Long = 0,
    val healthComparedAt: Long = 0,
    val events: List<AlarmEvent> = emptyList(),
    val profileSnapshot: String = "",
)

/** Trial assignment is frozen in each plan; reboots never make another random draw. */
object TrialAssignment {
    fun choose(mode: AlgorithmMode, trialIndex: Int, randomBit: Boolean): AlgorithmId = when (mode) {
        AlgorithmMode.ADAPTIVE -> AlgorithmId.ADAPTIVE_020
        AlgorithmMode.LEGACY -> AlgorithmId.LEGACY_011
        AlgorithmMode.ALTERNATE -> if (trialIndex % 2 == 0) AlgorithmId.ADAPTIVE_020 else AlgorithmId.LEGACY_011
        AlgorithmMode.RANDOM -> if (randomBit) AlgorithmId.ADAPTIVE_020 else AlgorithmId.LEGACY_011
    }
}
