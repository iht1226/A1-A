package dev.sleepalarm.model

import java.time.Duration
import java.time.ZoneId

/** Historical reference only. Unknown or incomplete stages never become invented sleep time. */
object SleepSummaries {
    fun knownSleepMinutes(sleep: SleepHistory): Long? {
        val total = Duration.between(sleep.start, sleep.end).toMillis()
        if (total <= 0) return null
        val stages = sleep.stages.sortedBy { it.start }
        if (stages.any { it.end <= it.start || it.start < sleep.start || it.end > sleep.end } ||
            stages.zipWithNext().any { (a,b) -> b.start < a.end }) return null
        val known = stages.filter { it.stage != HistoricalStage.UNKNOWN }
        if (known.sumOf { Duration.between(it.start, it.end).toMillis() }.toDouble() / total < 0.9) return null
        return known.filter { it.stage in setOf(HistoricalStage.SLEEPING, HistoricalStage.LIGHT, HistoricalStage.DEEP, HistoricalStage.REM) }
            .sumOf { Duration.between(it.start, it.end).toMillis() } / 60_000
    }
    fun context(records: List<SleepHistory>, now: Long, zone: ZoneId = ZoneId.systemDefault()): SleepContext {
        val valid = records.distinctBy { it.id }.filter { it.end.toEpochMilli() <= now && it.end.toEpochMilli() >= now - 7 * 86_400_000L }
            .mapNotNull { r -> knownSleepMinutes(r)?.takeIf { it in 30..900 }?.let { r to it } }
            .groupBy { it.first.end.atZone(zone).toLocalDate() }.values.mapNotNull { day -> day.maxByOrNull { it.second } }
        return SleepContext(if (valid.isEmpty()) 0.0 else valid.map { it.second }.average(), valid.size,
            valid.maxOfOrNull { it.first.end.toEpochMilli() } ?: 0, now)
    }
    fun attach(report: NightReport, records: List<SleepHistory>, now: Long): NightReport {
        if (report.firstRingAt == 0L) return report
        val candidate = records.filter { it.end.toEpochMilli() <= now && kotlin.math.abs(it.end.toEpochMilli() - report.firstRingAt) < 6 * 3_600_000 }
            .minByOrNull { kotlin.math.abs(it.end.toEpochMilli() - report.firstRingAt) } ?: return report
        return report.copy(details = report.details.copy(healthSleepMinutes = knownSleepMinutes(candidate) ?: -1,
            healthSleepEnd = candidate.end.toEpochMilli(), healthComparedAt = now))
    }
}
