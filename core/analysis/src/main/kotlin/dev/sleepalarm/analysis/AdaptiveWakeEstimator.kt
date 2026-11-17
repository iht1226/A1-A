package dev.sleepalarm.analysis

import dev.sleepalarm.model.*
import kotlin.math.*

/** Versioned engineering hypotheses, not fitted physiological constants or sleep-stage labels. */
data class AdaptiveParameters(
    val epochMillis: Long = 60_000,
    val epochs: Int = 3,
    val baselineCoveredMillis: Long = 300_000,
    val minCoverage: Double = 0.80,
    val maxGapMillis: Long = 15_000,
    val motionNoiseFloor: Double = 0.012,
    val madMultiplier: Double = 3.0,
    val scaleFloor: Double = 0.003,
    val activeFractionTarget: Double = 0.25,
    val intensityScaleMultiple: Double = 2.0,
    val burstTargetMillis: Long = 10_000,
    val trendRetention: Double = 0.70,
    val artifactLimit: Double = 1.5,
    val minHeartQuality: Double = 0.70,
    val heartFreshnessMillis: Long = 90_000,
    val heartScaleFloor: Double = 2.0,
    val supportedIntensityFloor: Double = 0.60,
    val earlySleepCost: Double = 0.06,
    val nearDeadlineTightening: Double = 0.10,
    val maxSleepDebtPenalty: Double = 0.08,
    val referenceSleepMinutes: Double = 450.0,
) {
    init {
        require(listOf(minCoverage, motionNoiseFloor, madMultiplier, scaleFloor, activeFractionTarget,
            trendRetention, artifactLimit, minHeartQuality, heartScaleFloor, supportedIntensityFloor,
            earlySleepCost, nearDeadlineTightening, maxSleepDebtPenalty, referenceSleepMinutes).all { it.isFinite() })
        require(epochMillis >= 30_000 && epochs >= 3 && baselineCoveredMillis >= 180_000)
        require(minCoverage in 0.7..1.0 && maxGapMillis in 5_000..30_000)
        require(motionNoiseFloor > 0 && scaleFloor > 0 && madMultiplier > 0)
        require(activeFractionTarget in 0.1..1.0 && burstTargetMillis >= 5_000)
        require(intensityScaleMultiple >= 1.0 && intensityScaleMultiple.isFinite())
        require(trendRetention in 0.0..1.0 && artifactLimit > motionNoiseFloor)
        require(minHeartQuality in 0.0..1.0 && heartScaleFloor > 0)
        require(supportedIntensityFloor in 0.0..1.0)
        require(earlySleepCost >= 0 && nearDeadlineTightening >= earlySleepCost)
    }
}

data class PersonalReference(
    val motionMedians: List<Double> = emptyList(),
    val motionMads: List<Double> = emptyList(),
    val thresholdOffset: Double = 0.0,
    val feedbackNights: Int = 0,
    val sleepContext: SleepContext = SleepContext(),
)

object RobustStats {
    fun quantile(values: List<Double>, fraction: Double): Double {
        val s = values.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return 0.0
        val index = (s.size - 1) * fraction.coerceIn(0.0, 1.0)
        val low = floor(index).toInt(); val high = ceil(index).toInt()
        return s[low] + (s[high] - s[low]) * (index - low)
    }
    fun median(v: List<Double>) = quantile(v, 0.5)
    fun mad(v: List<Double>): Double { val m = median(v); return median(v.map { abs(it - m) }) }
}

/** Motion-first, reliability-gated corroboration. No fixed sum of sensor weights. */
class AdaptiveWakeEstimator(
    private val parameters: AdaptiveParameters = AdaptiveParameters(),
    private val reference: PersonalReference = PersonalReference(),
    private val userThreshold: Double = 0.72,
) : WakeEstimator {
    init { require(userThreshold.isFinite() && userThreshold in 0.65..0.90) }
    private val p get() = parameters

    fun requiredEvidence(now: Long, windowStart: Long, deadline: Long): Double {
        val progress = ((now - windowStart).toDouble() / (deadline - windowStart).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val context = reference.sleepContext
        val debt = if (context.usable(windowStart)) ((p.referenceSleepMinutes - context.meanMinutes) / p.referenceSleepMinutes)
            .coerceIn(0.0, p.maxSleepDebtPenalty) else 0.0
        // Tightening grows at least as fast as the declining sleep-loss cost: time alone never helps.
        return (userThreshold + reference.thresholdOffset.coerceIn(-0.04, 0.04) +
            p.earlySleepCost * (1 - progress) + p.nearDeadlineTightening * progress + debt).coerceIn(0.65, 0.99)
    }

    override fun evaluate(now: Long, windowStart: Long, deadline: Long, motion: List<MotionSample>, heart: List<HeartSample>): WakeDecision {
        fun hold(reason: String, evidence: WakeEvidence? = null) = WakeDecision(false, 0.0, reason, evidence)
        if (deadline - windowStart !in 600_000L..3_600_000L || now < windowStart || now >= deadline)
            return hold("스마트 판단 범위 밖")
        if (now - windowStart < p.epochMillis * p.epochs) return hold("스마트 범위의 여러 관측 구간 확인 중")
        val recentStart = now - p.epochMillis * p.epochs
        val all = motion.filter { it.at <= now && it.receivedAt <= now && it.at % 5_000L == 0L && it.energy.isFinite() &&
            it.energy >= 0 && it.coveredMillis in 1..5_000 }.distinctBy { it.at }.sortedBy { it.at }
        val baseline = all.filter { it.at >= windowStart - 15 * 60_000 && it.at + 5_000 <= windowStart }
        if (baseline.sumOf { it.coveredMillis } < p.baselineCoveredMillis) return hold("개인 움직임 기준선 수집 부족")
        val baseCoverage = (baseline.sumOf { it.coveredMillis }.toDouble() /
            (windowStart - baseline.first().at).coerceAtLeast(1)).coerceAtMost(1.0)
        if (baseCoverage < p.minCoverage) return hold("기준선 관측 공백이 큼")
        val baseValues = baseline.filter { it.energy <= p.artifactLimit }.map { it.energy }
        if (baseValues.size < baseline.size * 0.95) return hold("기준선 센서 오류·충격 과다")
        val ownMedian = RobustStats.median(baseValues)
        val ownMad = RobustStats.mad(baseValues)
        val median = RobustStats.median(listOf(ownMedian) + reference.motionMedians.takeLast(7))
        val mad = max(ownMad, RobustStats.median(reference.motionMads.takeLast(7)))
        val activeCut = max(p.motionNoiseFloor, median + p.madMultiplier * max(mad, p.scaleFloor))
        val scale = max(p.scaleFloor, activeCut - median)
        val recent = all.filter { it.at >= recentStart && it.at + 5_000 <= now }
        if (recent.isEmpty() || now - (recent.last().at + 5_000) > p.maxGapMillis)
            return hold("최근 움직임 데이터 없음")
        if (recent.any { it.energy > p.artifactLimit }) return hold("최근 충격·센서 오류: 조기 판단 보류")
        var quality = baseCoverage
        val strengths = mutableListOf<Double>()
        val frequencies = mutableListOf<Double>()
        val durations = mutableListOf<Double>()
        val intensities = mutableListOf<Double>()
        for (i in 0 until p.epochs) {
            val from = recentStart + i * p.epochMillis
            val until = from + p.epochMillis
            val epoch = recent.filter { it.at >= from && it.at + 5_000 <= until }
            val coverage = epoch.sumOf { it.coveredMillis }.toDouble() / p.epochMillis
            if (coverage < p.minCoverage || epoch.zipWithNext().any { (a,b) -> b.at - a.at > p.maxGapMillis })
                return hold("관측 구간의 움직임 신뢰도 부족")
            quality = min(quality, coverage)
            val strength = RobustStats.quantile(epoch.map { it.energy }, 0.90)
            strengths += strength
            intensities += ((strength - median) / (scale * p.intensityScaleMultiple)).coerceIn(0.0, 1.0)
            val active = epoch.filter { it.energy > activeCut }
            frequencies += (active.sumOf { it.coveredMillis }.toDouble() /
                epoch.sumOf { it.coveredMillis }.coerceAtLeast(1) / p.activeFractionTarget).coerceIn(0.0, 1.0)
            var burst = 0L; var longest = 0L; var previous = Long.MIN_VALUE
            epoch.forEach {
                burst = if (it.energy > activeCut) {
                    if (previous != Long.MIN_VALUE && it.at - previous <= 5_000) burst + it.coveredMillis else it.coveredMillis
                } else 0
                previous = it.at; longest = max(longest, burst)
            }
            durations += (longest.toDouble() / p.burstTargetMillis).coerceIn(0.0, 1.0)
        }
        val trend = if (strengths.last() >= strengths.first() * p.trendRetention)
            ((strengths.last() - median) / scale).coerceIn(0.0, 1.0) else 0.0
        val intensity = intensities.min()
        val frequency = frequencies.min()
        val duration = durations.min()
        val core = minOf(frequency, duration, trend, quality)
        val hr = heart.filter { it.at <= now && it.receivedAt <= now && it.bpm.isFinite() && it.bpm in 35.0..180.0 &&
            it.quality.isFinite() && it.quality in p.minHeartQuality..1.0 }.distinctBy { it.at }.sortedBy { it.at }
        val hrBase = hr.filter { it.at >= windowStart - 15 * 60_000 && it.at < windowStart }
        val hrRecent = hr.filter { it.at >= recentStart }
        val hrMedian = RobustStats.median(hrBase.map { it.bpm })
        val hrMad = RobustStats.mad(hrBase.map { it.bpm })
        val usableHeart = hrBase.size >= 3 && hrBase.last().at - hrBase.first().at >= 180_000 &&
            hrRecent.size >= 3 && hrRecent.last().at - hrRecent.first().at >= 60_000 &&
            now - hrRecent.last().at <= p.heartFreshnessMillis &&
            hrRecent.zipWithNext().all { (a,b) -> b.at - a.at <= p.heartFreshnessMillis }
        val heartQuality = if (usableHeart) hrRecent.minOf { it.quality } else 0.0
        val heartSupport = if (usableHeart) ((RobustStats.median(hrRecent.map { it.bpm }) - hrMedian) /
            max(p.heartScaleFloor, hrMad * 1.4826)).coerceIn(0.0, 1.0) else 0.0
        val required = requiredEvidence(now, windowStart, deadline)
        val motionEvidence = min(core, intensity)
        // HR can corroborate an ambiguous intensity, but never replace duration/frequency/trend.
        val jointEvidence = if (intensity >= p.supportedIntensityFloor) minOf(core, heartSupport, heartQuality) else 0.0
        val joint = jointEvidence > motionEvidence
        val score = max(motionEvidence, jointEvidence)
        val evidence = WakeEvidence(quality, heartQuality, ownMedian, ownMad, hrMedian, hrMad,
            intensity, frequency, duration, trend, required, if (joint) "MOTION_HR_CORROBORATED" else "MOTION_ONLY")
        return WakeDecision(score >= required, score,
            if (score >= required) "개인 기준선 대비 반복 움직임·지속·추세 근거 (${evidence.fusion}, 수면 단계 아님)"
            else "반복 움직임 근거/이득이 수면 손실 비용·보수 기준에 미달", evidence)
    }
}
