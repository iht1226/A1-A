package dev.sleepalarm.alarm

import android.content.Context
import android.util.Log
import dev.sleepalarm.model.AlarmPlan
import dev.sleepalarm.settings.SettingsStore

internal object AlarmFailureReporter {
    fun report(context: Context, p: AlarmPlan, stage: String, error: Exception) {
        Log.e("SleepAlarm", "$stage failed for ${p.id}", error)
        val fallback = AlarmNotifications.fallback(context, p)
        val message = "$stage 실패 (${error.javaClass.simpleName}). " +
            "대체 알림 ${if (fallback.notificationPosted) "요청됨" else "실패"}, " +
            "진동 ${if (fallback.vibrationStarted) "요청됨" else "실패"}. " +
            fallback.limitations.joinToString(" · ") +
            " 워치 앱 설정에서 알림·전체 화면·알람 권한을 확인하세요. 시스템 제한으로 소리·진동을 보장할 수 없습니다."
        // Logging/fallback must survive even unavailable or full storage.
        runCatching { SettingsStore(context).alarmError(message) }
            .onFailure { Log.e("SleepAlarm", "Could not persist alarm failure", it) }
    }
}
