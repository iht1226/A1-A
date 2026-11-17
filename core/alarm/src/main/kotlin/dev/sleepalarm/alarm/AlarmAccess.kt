package dev.sleepalarm.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Diagnostic observations, never infer notification access from exact-alarm access. */
data class AlarmAccess(val exact: Boolean, val exactGrant: Boolean, val notificationRuntime: Boolean,
    val notifications: Boolean, val channelEnabled: Boolean, val fullScreen: Boolean, val sdk: Int) {
    fun text(): String = "정확한 알람 API: ${if (exact) "예약 가능" else "예약 불가"}\n" +
        "${if (sdk >= 33) "USE_EXACT_ALARM 설치 권한" else "SCHEDULE_EXACT_ALARM"}: ${if (exactGrant) "허용" else "미허용"}\n" +
        "일반 알림 권한: ${if (notificationRuntime) "허용" else "미허용"}\n" +
        "앱 알림 설정: ${if (notifications) "켜짐" else "꺼짐"} · 알람 채널: ${if (channelEnabled) "허용/미생성" else "차단"}\n" +
        "전체 화면 알림: ${if (fullScreen) "허용" else "제한"} · Android API $sdk\n" +
        "알림 차단은 정확한 알람 권한과 별개입니다. 소리·진동·잠금 화면 전달은 실제 시험이 필요합니다."
    companion object {
        fun canSchedule(c: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            runCatching { c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)
        fun inspect(c: Context): AlarmAccess {
            val sdk = Build.VERSION.SDK_INT
            val nm = c.getSystemService(NotificationManager::class.java)
            fun granted(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            val exact = canSchedule(c)
            return AlarmAccess(exact, if (sdk >= 33) granted(Manifest.permission.USE_EXACT_ALARM) else exact,
                sdk < 33 || granted(Manifest.permission.POST_NOTIFICATIONS), nm.areNotificationsEnabled(),
                nm.getNotificationChannel("wake-v1")?.importance != NotificationManager.IMPORTANCE_NONE,
                if (Build.VERSION.SDK_INT >= 34) runCatching { nm.canUseFullScreenIntent() }.getOrDefault(false) else true, sdk)
        }
    }
}

object SafeSettings {
    /** Some Wear firmwares have neither special access nor app-details activities. */
    fun open(c: Context, action: String): String {
        val actions = listOf(action, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS).distinct()
        val errors = mutableListOf<String>()
        actions.forEach { candidate ->
            val intent = Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (candidate == Settings.ACTION_APP_NOTIFICATION_SETTINGS) intent.putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            if (candidate != Settings.ACTION_SETTINGS) intent.data = Uri.parse("package:${c.packageName}")
            val result = runCatching { c.startActivity(intent) }
            if (result.isSuccess) return if (candidate == action) "설정에서 돌아오면 상태를 다시 읽습니다."
                else "전용 설정 화면이 없어 대체 화면을 열었습니다. 워치 설정 → 앱 → 수면 알람 → 권한/알림을 확인하세요."
            errors += result.exceptionOrNull()!!.javaClass.simpleName
        }
        return "이 워치는 해당 설정 화면을 제공하지 않거나 접근을 차단했습니다 (${errors.distinct().joinToString()}). 워치 설정 → 앱 → 수면 알람 → 권한/알림을 직접 확인하세요."
    }
}
