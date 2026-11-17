package dev.sleepalarm.analysis

/** Keep the same quality gates, but reserve useful decision time in a short window. */
data class WakeTiming(val baselineMillis: Long, val recentMillis: Long) {
    val observationMillis: Long get() = baselineMillis + recentMillis

    companion object {
        const val EVALUATION_MILLIS = 30_000L
        const val CONFIRMATION_MILLIS = 60_000L
        fun forWindow(windowMillis: Long): WakeTiming {
            require(windowMillis in 10 * 60_000L..60 * 60_000L)
            return if (windowMillis < 15 * 60_000L) WakeTiming(4 * 60_000L, 2 * 60_000L)
            else WakeTiming(6 * 60_000L, 3 * 60_000L)
        }
    }
}

/** Elapsed time, never wall-clock time. A missed evaluation breaks continuous confirmation. */
class SustainedWakeGate {
    private var since: Long? = null
    private var previous: Long? = null

    fun update(eligible: Boolean, elapsed: Long): Boolean {
        val last = previous
        if (!eligible || (last != null && (elapsed <= last || elapsed - last > 45_000))) since = null
        previous = elapsed
        if (!eligible) return false
        val start = since ?: elapsed.also { since = it }
        return elapsed - start >= WakeTiming.CONFIRMATION_MILLIS
    }
}
