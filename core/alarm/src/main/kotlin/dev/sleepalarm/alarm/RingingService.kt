package dev.sleepalarm.alarm

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.sleepalarm.model.AlarmPlan
import dev.sleepalarm.settings.SettingsStore

open class RingingService : Service() {
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var currentNonce = ""
    private var ownsVibration = false
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = try { SettingsStore(this).plan() } catch (e: Exception) {
            Log.e("SleepAlarm", "Could not read ringing plan", e)
            stopSelf(); return START_NOT_STICKY
        }
        if (p == null || !p.enabled || !p.ringing) { stopSelf(); return START_NOT_STICKY }
        // Catch promotion errors in the service: the caller cannot catch asynchronous failures here.
        try { promote(p) } catch (e: Exception) {
            releaseResources()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            // Separate notification ID survives this service's later onDestroy().
            AlarmFailureReporter.report(this, p, "알람 서비스 전경 실행", e)
            return START_NOT_STICKY
        }
        runCatching { AlarmNotifications.cancelFallback(this) }
        if (currentNonce == p.ringNonce) return START_STICKY
        releaseResources()
        currentNonce = p.ringNonce
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "sleepalarm:ring",
            ).apply { acquire(10 * 60_000L) }
        }.onFailure { Log.e("SleepAlarm", "Alarm wake lock unavailable", it) }
        runCatching { ownsVibration = AlarmNotifications.vibrate(this, p.settings, true) }
            .onFailure { Log.e("SleepAlarm", "Alarm vibration unavailable", it) }
        try {
            val audio = getSystemService(AudioManager::class.java)
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
                .also { audio.requestAudioFocus(it) }
            val candidate = MediaPlayer()
            player = candidate
            candidate.apply {
                setAudioAttributes(attributes)
                setWakeMode(this@RingingService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@RingingService, Settings.System.DEFAULT_ALARM_ALERT_URI)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Keep the foreground notification and any continuous vibration; add an audible
            // system-notification attempt without letting a failed player crash the service.
            runCatching { player?.release() }; player = null
            AlarmFailureReporter.report(this, p, "알람 소리 재생", e)
            if (ownsVibration) runCatching { AlarmNotifications.vibrate(this, p.settings, true) }
        }
        return START_STICKY
    }

    /** Android boundary kept overridable for simulated Security/FGS denial regression tests. */
    protected open fun promote(plan: AlarmPlan) {
        ServiceCompat.startForeground(this, AlarmNotifications.ID, AlarmNotifications.create(this, plan),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun releaseResources() {
        runCatching { player?.release() }; player = null
        if (ownsVibration) runCatching { AlarmNotifications.vibrator(this).cancel() }
        ownsVibration = false
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
        runCatching { focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) } }; focus = null
    }

    override fun onDestroy() {
        releaseResources()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
