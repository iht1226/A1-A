package dev.sleepalarm.analysis

import dev.sleepalarm.model.*

/** Uncalibrated wellness heuristic. No sleep-stage classifier and no medical claims. */
class ConservativeWakeEstimator : WakeEstimator {
    override fun evaluate(
        now: Long,
        windowStart: Long,
        deadline: Long,
        motion: List<MotionSample>,
        heart: List<HeartSample>,
    ): WakeDecision {
        fun hold(reason: String) = WakeDecision(false, 0.0, reason)
        if (now < windowStart || now >= deadline) return hold("스마트 알람 범위 밖")
        val windowMillis = deadline - windowStart
        if (windowMillis !in 10 * MINUTE..60 * MINUTE) return hold("지원하지 않는 스마트 범위")
        val timing = WakeTiming.forWindow(windowMillis)
        if (now - windowStart < timing.observationMillis) return hold("기준 데이터 수집 중")
        val split = now - timing.recentMillis
        val baselineStart = now - timing.observationMillis
        // Deduplicate fixed five-second bins: callback frequency must not inflate confidence.
        val bins = motion.filter {
            it.at in baselineStart..now && it.at % 5_000L == 0L &&
                it.energy.isFinite() && it.energy >= 0 && it.coveredMillis in 1..5_000
        }.distinctBy { it.at }
        fun coverage(from: Long, until: Long): Double = bins.filter {
            it.at >= from && it.at + 5_000 <= until
        }.sumOf { it.coveredMillis }.toDouble() / (until - from)
        if (coverage(baselineStart, split) < 0.7 || coverage(split, now) < 0.7)
            return hold("움직임 관측 공백이 큼")
        if (bins.maxOfOrNull { it.at + 5_000 }?.let { now - it > 15_000 } != false)
            return hold("최근 움직임 데이터 없음")

        val hr = heart.filter { it.at in baselineStart..now && it.bpm.isFinite() && it.bpm in 35.0..180.0 }
            .distinctBy { it.at }.sortedBy { it.at }
        val base = hr.filter { it.at < split }
        val recent = hr.filter { it.at >= split }
        // Sparse/batched data is explicitly permitted, but stale or burst-only data is rejected.
        fun enough(points: List<HeartSample>, minSpan: Long) =
            points.size >= 3 && points.last().at - points.first().at >= minSpan &&
                points.zipWithNext().all { (a, b) -> b.at - a.at <= 150_000 }
        if (!enough(base, 180_000) || !enough(recent, 90_000) ||
            now - hr.last().at > 90_000 || base.first().at - baselineStart > 90_000 ||
            split - base.last().at > 90_000 || recent.first().at - split > 90_000)
            return hold("심박 데이터 부족 또는 지연")
        fun median(v: List<Double>) = v.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
        val rise = median(recent.map { it.bpm }) - median(base.map { it.bpm })
        val recentMotion = bins.filter { it.at >= split && it.at + 5_000 <= now }
        val baselineMotion = bins.filter { it.at < split }
        fun mean(v: List<MotionSample>) = v.sumOf { it.energy * it.coveredMillis } / v.sumOf { it.coveredMillis }
        val activeFraction = recentMotion.count { it.energy in 0.025..0.6 }.toDouble() / recentMotion.size
        if (recentMotion.any { it.energy > 1.5 } || rise > 18)
            return hold("큰 움직임·급격한 심박 변화: 판단 보류")
        if (rise !in 4.0..18.0 || activeFraction < 0.25 || mean(recentMotion) < mean(baselineMotion) + 0.01)
            return hold("조기 기상 근거 부족")
        return WakeDecision(true, 0.8, "지속된 심박 상승과 움직임 증가 관측 (수면 단계 아님)")
    }

    private companion object { const val MINUTE = 60_000L }
}
