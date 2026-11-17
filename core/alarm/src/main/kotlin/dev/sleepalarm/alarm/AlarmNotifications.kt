package dev.sleepalarm.alarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import dev.sleepalarm.model.*

object AlarmNotifications {
    const val ID = 700
    const val FALLBACK_ID = 702
    const val FALLBACK_CHANNEL = "wake-fallback-v1"
    fun create(context: Context, plan: AlarmPlan, fallback: Boolean = false): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (fallback) FALLBACK_CHANNEL else "wake-v1"
        manager.createNotificationChannel(NotificationChannel(channel, if (fallback) "기상 알람 대체 알림" else "기상 알람", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(if (fallback) Settings.System.DEFAULT_ALARM_ALERT_URI else null,
                if (fallback) AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build() else null)
            enableVibration(fallback)
            if (fallback) vibrationPattern = longArrayOf(0, 400, 600, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        val full = PendingIntent.getActivity(context, 7,
            Intent().setClassName(context.packageName, "dev.sleepalarm.wear.AlarmActivity").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(name: String) = PendingIntent.getBroadcast(context, 0,
            Intent(context, AlarmReceiver::class.java).apply {
                action = name; data = Uri.parse("sleepalarm://action/$name/${plan.ringNonce}")
                putExtra("nonce", plan.ringNonce)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (plan.ringKind == RingKind.SMART) "스마트 기상 알람" else "기상 알람")
            .setContentText(if (fallback) "알람 서비스 제한으로 대체 알림을 사용합니다. 해제 또는 다시 알림을 선택하세요." else "기상하려면 해제, 더 자려면 다시 알림")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setOngoing(true)
            .setContentIntent(full).setFullScreenIntent(full, true)
            .addAction(0, "해제", action("dismiss"))
            .addAction(0, "${plan.settings.snoozeMinutes}분 뒤", action("snooze-action"))
            .build()
    }
    fun vibrator(context: Context): Vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

    fun vibrate(context: Context, settings: AlarmSettings, repeat: Boolean): Boolean {
        val v = vibrator(context)
        if (!v.hasVibrator()) return false
        val timings = when (settings.pattern) {
            VibrationPattern.GENTLE -> longArrayOf(0, 400, 600, 400, 1_200)
            VibrationPattern.PULSE -> longArrayOf(0, 180, 180, 180, 180, 600, 900)
            VibrationPattern.URGENT -> longArrayOf(0, 800, 200, 800, 300)
        }
        val amplitudes = IntArray(timings.size) { index ->
            if (index % 2 == 0) 0 else if (v.hasAmplitudeControl()) settings.amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        }
        val effect = VibrationEffect.createWaveform(timings, amplitudes, if (repeat) 0 else -1)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        v.vibrate(effect, attributes)
        return true
    }

    data class FallbackOutcome(val notificationPosted: Boolean, val vibrationStarted: Boolean, val limitations: List<String>)

    fun fallback(context: Context, p: AlarmPlan): FallbackOutcome {
        val limitations = mutableListOf<String>()
        val posted = runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            check(manager.areNotificationsEnabled()) { "앱 알림 권한 또는 시스템 알림 설정이 차단됨" }
            val notification = create(context, p, fallback = true)
            check(manager.getNotificationChannel(FALLBACK_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE) { "대체 알림 채널이 차단됨" }
            manager.notify(FALLBACK_ID, notification)
            true
        }.getOrElse { limitations += "알림: ${it.message ?: it.javaClass.simpleName}"; false }
        // Independent attempts: a denied notification must not prevent vibration.
        val vibrated = runCatching { vibrate(context, p.settings, false) }.getOrElse {
            limitations += "진동: ${it.message ?: it.javaClass.simpleName}"; false
        }
        if (!vibrated && limitations.none { it.startsWith("진동:") }) limitations += "진동 장치 없음"
        return FallbackOutcome(posted, vibrated, limitations)
    }

    fun cancelFallback(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(FALLBACK_ID)
    }
}
