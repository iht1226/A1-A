package dev.sleepalarm.settings

import dev.sleepalarm.model.*
import org.json.JSONObject

object JsonCodec {
    fun settings(s: AlarmSettings) = JSONObject().apply {
        put("hour", s.hour); put("minute", s.minute); put("window", s.windowMinutes)
        put("smart", s.smartEnabled); put("amplitude", s.amplitude)
        put("pattern", s.pattern.name); put("snooze", s.snoozeMinutes)
    }
    fun settings(j: JSONObject) = AlarmSettings(
        hour = j.getInt("hour"), minute = j.getInt("minute"), windowMinutes = j.getInt("window"),
        smartEnabled = j.getBoolean("smart"), amplitude = j.getInt("amplitude"),
        pattern = VibrationPattern.valueOf(j.getString("pattern")), snoozeMinutes = j.getInt("snooze"),
    )
    fun plan(p: AlarmPlan) = JSONObject().apply {
        put("id", p.id); put("armedAt", p.armedAt); put("deadline", p.deadline)
        put("settings", settings(p.settings)); put("enabled", p.enabled)
        put("early", p.earlyDelivered); put("final", p.finalDelivered); put("snoozeAt", p.snoozeAt)
        put("nonce", p.ringNonce); put("kind", p.ringKind?.name ?: "")
    }
    fun plan(j: JSONObject) = AlarmPlan(
        id = j.getString("id"), armedAt = j.getLong("armedAt"), deadline = j.getLong("deadline"),
        settings = settings(j.getJSONObject("settings")), enabled = j.getBoolean("enabled"),
        earlyDelivered = j.getBoolean("early"), finalDelivered = j.getBoolean("final"),
        snoozeAt = j.getLong("snoozeAt"), ringNonce = j.getString("nonce"),
        ringKind = j.getString("kind").takeIf { it.isNotEmpty() }?.let(RingKind::valueOf),
    )
    fun report(r: NightReport) = JSONObject().apply {
        put("id", r.id); put("armedAt", r.armedAt); put("deadline", r.deadline)
        put("firstRingAt", r.firstRingAt); put("firstRingKind", r.firstRingKind)
        put("dismissedAt", r.dismissedAt); put("motionBins", r.motionBins)
        put("heartSamples", r.heartSamples); put("decision", r.decision)
    }
    fun report(j: JSONObject) = NightReport(
        j.getString("id"), j.getLong("armedAt"), j.getLong("deadline"), j.optLong("firstRingAt"),
        j.optString("firstRingKind"), j.optLong("dismissedAt"), j.optInt("motionBins"),
        j.optInt("heartSamples"), j.optString("decision"),
    )
}
