package dev.sleepalarm.sensors

import android.content.*
import dev.sleepalarm.analysis.*
import dev.sleepalarm.diagnostics.DiagnosticArchive

class DiagnosticReplayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try { DiagnosticArchive(context).use { a ->
                val id = intent.getStringExtra("id").orEmpty().ifBlank { a.recent().firstOrNull()?.optString("id").orEmpty() }
                val j = a.get(id) ?: return@use
                if (j.optLong("stoppedAt") == 0L && System.currentTimeMillis() < j.optLong("plannedEnd")) {
                    dev.sleepalarm.settings.SettingsStore(context).status("리플레이는 수집 종료 후 실행하세요. 진행 중인 수집은 변경하지 않았습니다.")
                    return@use
                }
                j.put("replay", runCatching {
                    check(j.optLong("stoppedAt") > 0 || System.currentTimeMillis() >= j.optLong("plannedEnd")) { "수집 종료 후 실행하세요. 갑작스러운 종료는 실제 종료 시각을 확인할 수 없습니다." }
                    val ticks = a.ticks(id); check(ticks.isNotEmpty()) { "실제 평가 시각이 없습니다. 독립 센서 시험/이전 기록은 동일 결과 리플레이 대상이 아닙니다." }
                    val profile = j.getJSONObject("profile")
                    check(profile.getString("parameters") == AdaptiveParameters().toString()) { "파라미터 버전이 달라 정확한 재현을 보장할 수 없습니다." }
                    SensorDatabase(context).use { db ->
                        val motion = db.motion(id, j.getLong("plannedStart")); val heart = db.heart(id, j.getLong("plannedStart"))
                        fun ids(t: org.json.JSONObject, key: String): Set<Long> {
                            val a = t.getJSONArray(key); return (0 until a.length()).map { a.getLong(it) }.toSet()
                        }
                        check(ticks.flatMap { ids(it,"motionIds") }.all { at -> motion.any { it.at == at } } &&
                            ticks.flatMap { ids(it,"heartIds") }.all { at -> heart.any { it.at == at } }) { "원본 보관기간 경과/입력 누락: 재현 불가 (48시간 보관)" }
                        val replay = OfflineReplay.run(ticks.map { ReplayTick(it.getLong("now"), it.getLong("elapsed"), it.getInt("generation"), it.getBoolean("heartAllowed"), ids(it,"motionIds"), ids(it,"heartIds")) },
                            j.getLong("windowStart"), j.getLong("plannedEnd"), maxOf(j.optLong("observedUntil",j.getLong("heartbeat")), ticks.maxOf { it.getLong("now") }), motion, heart) {
                            AdaptiveWakeEstimator(reference = SmartWindowService.reference(profile), userThreshold = profile.getDouble("userThreshold"))
                        }
                        val mismatches = replay.indices.filter { i ->
                            val t = ticks[i]; val r = replay[i]
                            t.getLong("legacyAt") != r.legacyAt || t.getLong("adaptiveAt") != r.adaptiveAt ||
                                t.getBoolean("legacyWake") != r.legacy.wake || t.getBoolean("adaptiveWake") != r.adaptive.wake ||
                                t.getString("legacyReason") != r.legacy.reason || t.getString("adaptiveReason") != r.adaptive.reason ||
                                kotlin.math.abs(t.getDouble("legacyConfidence") - r.legacy.confidence) > 1e-9 || kotlin.math.abs(t.getDouble("adaptiveConfidence") - r.adaptive.confidence) > 1e-9
                        }
                        "저장 입력 ${ticks.size}회 평가 재생: ${replay.size - mismatches.size} 일치, ${mismatches.size} 불일치. 실기기 센서 검증이나 기상 효과 검증이 아닙니다."
                    }
                }.getOrElse { "리플레이 불가: ${it.message}" }); a.save(j)
            } } catch (e: Exception) {
                android.util.Log.e("SleepAlarm", "Replay failed; alarm reservation untouched", e)
                runCatching { dev.sleepalarm.settings.SettingsStore(context).status("리플레이 오류: ${e.javaClass.simpleName}; 정시 알람 유지") }
            } finally { pending.finish() }
        }.start()
    }
}
