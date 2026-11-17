package dev.sleepalarm.analysis

import dev.sleepalarm.model.*

data class Coverage(val from: Long, val until: Long, val observedMillis: Long, val maximumGapMillis: Long) {
    val ratio: Double get() = if (until <= from) 0.0 else observedMillis.toDouble() / (until - from)
}

object CapturePolicy {
    const val SAMPLING_US = 200_000
    const val BATCH_US = 5_000_000
    const val FUTURE_TOLERANCE_MS = 100L
    fun latencyUs(fifoMax: Int, fifoReserved: Int): Int {
        val capacity = if (fifoReserved > 0) fifoReserved else fifoMax
        return if (capacity <= 0) 0 else minOf(BATCH_US, (capacity.toLong() * SAMPLING_US / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
    fun reject(at: Long, received: Long, start: Long, end: Long, timestampNanos: Long, previousNanos: Long,
               energy: Double?, accuracy: Int): String? = when {
        timestampNanos <= 0 -> "INVALID_TIMESTAMP"
        timestampNanos <= previousNanos -> "DUPLICATE_OR_REORDERED"
        at < start -> "BEFORE_CAPTURE"
        at >= end -> "AFTER_CAPTURE"
        at > received + FUTURE_TOLERANCE_MS -> "FUTURE_TIMESTAMP"
        energy == null || !energy.isFinite() || energy < 0 -> "INVALID_VALUES"
        accuracy <= 0 -> "UNRELIABLE_ACCURACY"
        else -> null // A late arrival is retained, with its real arrival time; never backdated.
    }
}

object CoverageDiagnostics {
    fun measure(from: Long, until: Long, spans: List<Pair<Long, Long>>): Coverage {
        if (until <= from) return Coverage(from, from, 0, 0)
        var cursor = from; var covered = 0L; var longest = 0L
        spans.sortedBy { it.first }.forEach { (a, b) ->
            val left = maxOf(from, a); val right = minOf(until, b)
            if (right > left) {
                if (left > cursor) longest = maxOf(longest, left - cursor)
                val fresh = maxOf(cursor, left)
                if (right > fresh) covered += right - fresh
                cursor = maxOf(cursor, right)
            }
        }
        longest = maxOf(longest, until - cursor)
        return Coverage(from, until, covered, longest)
    }
    fun minutes(from: Long, until: Long, spans: List<Pair<Long, Long>>): List<Coverage> =
        if (until <= from) emptyList() else generateSequence(from) { it + 60_000 }.takeWhile { it < until }
            .map { measure(it, minOf(it + 60_000, until), spans) }.toList()
}

data class ReplayTick(val now: Long, val elapsed: Long, val generation: Int, val heartAllowed: Boolean, val motionIds: Set<Long>? = null, val heartIds: Set<Long>? = null)
/** No Android/hardware dependencies. Uses precisely the recorded evaluation times and arrival cutoff. */
object OfflineReplay {
    fun run(ticks: List<ReplayTick>, windowStart: Long, deadline: Long, observedUntil: Long,
            motion: List<MotionSample>, heart: List<HeartSample>, estimator: () -> WakeEstimator): List<ComparisonSnapshot> {
        var generation = Int.MIN_VALUE
        var comparison: CausalComparison? = null
        var lastNow = Long.MIN_VALUE
        return ticks.map { t ->
            require(t.now <= observedUntil && t.now > lastNow) { "Replay cannot invent future or reordered evaluations" }
            lastNow = t.now
            if (t.generation != generation) { comparison = CausalComparison(estimator()); generation = t.generation }
            comparison!!.update(t.now, t.elapsed, windowStart, deadline, motion.filter { t.motionIds == null || it.at in t.motionIds },
                if (t.heartAllowed) heart.filter { t.heartIds == null || it.at in t.heartIds } else emptyList(), observedUntil)
        }
    }
}
