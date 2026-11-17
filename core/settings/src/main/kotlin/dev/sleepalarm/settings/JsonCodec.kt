package dev.sleepalarm.settings

import dev.sleepalarm.model.*
import org.json.JSONObject
import org.json.JSONArray

object JsonCodec {
    fun settings(s: AlarmSettings) = JSONObject().apply {
        put("hour", s.hour); put("minute", s.minute); put("window", s.windowMinutes)
        put("smart", s.smartEnabled); put("amplitude", s.amplitude)
        put("pattern", s.pattern.name); put("snooze", s.snoozeMinutes)
        put("keepFinal", s.keepFinalAfterSmartDismiss); put("algorithmMode", s.algorithmMode.name); put("threshold", s.adaptiveThreshold)
    }
    fun settings(j: JSONObject) = AlarmSettings(
        hour = j.getInt("hour"), minute = j.getInt("minute"), windowMinutes = j.getInt("window"),
        smartEnabled = j.getBoolean("smart"), amplitude = j.getInt("amplitude"),
        pattern = VibrationPattern.valueOf(j.getString("pattern")), snoozeMinutes = j.getInt("snooze"),
        keepFinalAfterSmartDismiss = j.optBoolean("keepFinal", false),
        algorithmMode = enumValue(j.optString("algorithmMode"), AlgorithmMode.ADAPTIVE),
        adaptiveThreshold = j.optDouble("threshold", 0.72),
    )
    fun plan(p: AlarmPlan) = JSONObject().apply {
        put("id", p.id); put("armedAt", p.armedAt); put("deadline", p.deadline)
        put("settings", settings(p.settings)); put("enabled", p.enabled)
        put("early", p.earlyDelivered); put("final", p.finalDelivered); put("snoozeAt", p.snoozeAt)
        put("nonce", p.ringNonce); put("kind", p.ringKind?.name ?: ""); put("algorithm", p.algorithm.name)
    }
    fun plan(j: JSONObject) = AlarmPlan(
        id = j.getString("id"), armedAt = j.getLong("armedAt"), deadline = j.getLong("deadline"),
        settings = settings(j.getJSONObject("settings")), enabled = j.getBoolean("enabled"),
        earlyDelivered = j.getBoolean("early"), finalDelivered = j.getBoolean("final"),
        snoozeAt = j.getLong("snoozeAt"), ringNonce = j.getString("nonce"),
        ringKind = j.getString("kind").takeIf { it.isNotEmpty() }?.let(RingKind::valueOf),
        algorithm = enumValue(j.optString("algorithm"), AlgorithmId.LEGACY_011),
    )
    fun report(r: NightReport) = JSONObject().apply {
        put("id", r.id); put("armedAt", r.armedAt); put("deadline", r.deadline)
        put("firstRingAt", r.firstRingAt); put("firstRingKind", r.firstRingKind)
        put("dismissedAt", r.dismissedAt); put("motionBins", r.motionBins)
        put("heartSamples", r.heartSamples); put("decision", r.decision); put("details", details(r.details))
    }
    fun report(j: JSONObject) = NightReport(
        j.getString("id"), j.getLong("armedAt"), j.getLong("deadline"), j.optLong("firstRingAt"),
        j.optString("firstRingKind"), j.optLong("dismissedAt"), j.optInt("motionBins"),
        j.optInt("heartSamples"), j.optString("decision"),
        j.optJSONObject("details")?.let(::details) ?: NightDetails(),
    )

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
    fun details(d: NightDetails) = JSONObject().apply {
        put("profileSnapshot", d.profileSnapshot)
        put("smartScheduled", d.smartScheduled); put("testAlarm", d.testAlarm)
        put("legacyConfidence", d.legacyConfidence); put("adaptiveConfidence", d.adaptiveConfidence)
        put("algorithm", d.algorithm.name)
        put("mode", d.mode.name)
        put("observationEnd", d.observationEnd)
        put("legacySuggestedAt", d.legacySuggestedAt)
        put("adaptiveSuggestedAt", d.adaptiveSuggestedAt)
        put("legacyReason", d.legacyReason)
        put("adaptiveReason", d.adaptiveReason)
        put("motionQuality", d.motionQuality)
        put("heartQuality", d.heartQuality)
        put("referenceMedian", d.referenceMedian)
        put("referenceMad", d.referenceMad)
        put("referenceHeart", d.referenceHeart)
        put("referenceHeartMad", d.referenceHeartMad)
        put("personalizationOffset", d.personalizationOffset)
        put("personalizedNights", d.personalizedNights)
        put("batteryStart", d.batteryStart)
        put("batteryEnd", d.batteryEnd)
        put("batteryStartedAt", d.batteryStartedAt)
        put("outputStartedAt", d.outputStartedAt)
        put("firstDismissedAt", d.firstDismissedAt)
        put("refreshed", d.refreshed)
        put("sleepiness", d.sleepiness)
        put("feedbackAt", d.feedbackAt)
        put("healthSleepMinutes", d.healthSleepMinutes)
        put("healthSleepEnd", d.healthSleepEnd)
        put("healthComparedAt", d.healthComparedAt)
        put("events", JSONArray(d.events.takeLast(100).map { JSONObject().put("at", it.at).put("kind", it.kind).put("action", it.action) }))
    }
    fun details(j: JSONObject) = NightDetails(
        profileSnapshot = j.optString("profileSnapshot", ""),
        smartScheduled = j.optBoolean("smartScheduled", false), testAlarm = j.optBoolean("testAlarm", false),
        legacyConfidence = j.optDouble("legacyConfidence", 0.0), adaptiveConfidence = j.optDouble("adaptiveConfidence", 0.0),
        algorithm = enumValue(j.optString("algorithm"), AlgorithmId.LEGACY_011),
        mode = enumValue(j.optString("mode"), AlgorithmMode.LEGACY),
        observationEnd = j.optLong("observationEnd", 0),
        legacySuggestedAt = j.optLong("legacySuggestedAt", 0),
        adaptiveSuggestedAt = j.optLong("adaptiveSuggestedAt", 0),
        legacyReason = j.optString("legacyReason", "평가 전"),
        adaptiveReason = j.optString("adaptiveReason", "평가 전"),
        motionQuality = j.optDouble("motionQuality", 0.0),
        heartQuality = j.optDouble("heartQuality", 0.0),
        referenceMedian = j.optDouble("referenceMedian", 0.0),
        referenceMad = j.optDouble("referenceMad", 0.0),
        referenceHeart = j.optDouble("referenceHeart", 0.0),
        referenceHeartMad = j.optDouble("referenceHeartMad", 0.0),
        personalizationOffset = j.optDouble("personalizationOffset", 0.0),
        personalizedNights = j.optInt("personalizedNights", 0),
        batteryStart = j.optInt("batteryStart", -1),
        batteryEnd = j.optInt("batteryEnd", -1),
        batteryStartedAt = j.optLong("batteryStartedAt", 0),
        outputStartedAt = j.optLong("outputStartedAt", 0),
        firstDismissedAt = j.optLong("firstDismissedAt", 0),
        refreshed = j.optInt("refreshed", 0),
        sleepiness = j.optInt("sleepiness", 0),
        feedbackAt = j.optLong("feedbackAt", 0),
        healthSleepMinutes = j.optLong("healthSleepMinutes", -1),
        healthSleepEnd = j.optLong("healthSleepEnd", 0),
        healthComparedAt = j.optLong("healthComparedAt", 0),
        events = j.optJSONArray("events")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { AlarmEvent(it.getLong("at"), it.getString("kind"), it.getString("action")) } } } ?: emptyList(),
    )
    fun context(c: SleepContext) = JSONObject().put("meanMinutes", c.meanMinutes).put("nights", c.nights).put("lastSleepEnd", c.lastSleepEnd).put("recordedAt", c.recordedAt)
    fun context(j: JSONObject) = SleepContext(j.optDouble("meanMinutes", 0.0), j.optInt("nights"), j.optLong("lastSleepEnd"), j.optLong("recordedAt"))
}
