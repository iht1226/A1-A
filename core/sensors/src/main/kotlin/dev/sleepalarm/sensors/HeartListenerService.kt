package dev.sleepalarm.sensors

import android.os.SystemClock
import android.content.Intent
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.HeartRateAccuracy
import dev.sleepalarm.model.HeartSample
import dev.sleepalarm.settings.SettingsStore

class HeartListenerService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        runCatching {
            val run = CaptureRegistry.active ?: return
            val id = run.id
            val now = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            val bootAt = now - elapsed
            val samples = dataPoints.getData(DataType.HEART_RATE_BPM).mapNotNull {
                val quality = (it.accuracy as? HeartRateAccuracy)?.sensorStatus
                val timeSinceBoot = it.timeDurationFromBoot.toMillis()
                val at = bootAt + timeSinceBoot
                if (timeSinceBoot !in 0..elapsed || at !in run.start..minOf(now, run.end) ||
                    !it.value.isFinite() || it.value !in 35.0..180.0) null
                else HeartSample(at, it.value, when (quality) {
                    HeartRateAccuracy.SensorStatus.ACCURACY_HIGH -> 1.0
                    HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM -> 0.75
                    HeartRateAccuracy.SensorStatus.ACCURACY_LOW -> 0.25
                    else -> 0.0
                }, now)
            }
            SensorDatabase(this).use { db -> db.addHeart(id, samples) }
        }.onFailure {
            SettingsStore(this).status("심박 기록 실패: 일반 알람 유지")
            runCatching { dev.sleepalarm.diagnostics.DiagnosticArchive(this).use { a -> a.recent().firstOrNull()?.let { j -> a.event(j.getString("id"), "HEART_DB_ERROR ${it.javaClass.simpleName}") } } }
        }
    }
    override fun onPermissionLost() {
        val store = SettingsStore(this)
        runCatching { dev.sleepalarm.diagnostics.DiagnosticArchive(this).use { a -> a.recent().firstOrNull { it.optLong("stoppedAt") == 0L }?.let { a.event(it.getString("id"), "HEART_PERMISSION_LOST") } } }
        store.status("심박 권한 취소됨: 허용된 움직임 분석만 사용, 최종 알람 유지")
    }
}
