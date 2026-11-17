package dev.sleepalarm.alarm

import android.app.*
import android.content.*
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.SettingsStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

class SecurityDeniedPromotionService : RingingService() {
    override fun promote(plan: AlarmPlan) { throw SecurityException("FGS permission denied") }
}
class StateDeniedPromotionService : RingingService() {
    override fun promote(plan: AlarmPlan) { throw IllegalStateException("FGS start not allowed") }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmFailureTest {
    private lateinit var app: Application
    private lateinit var store: SettingsStore
    private lateinit var notifications: NotificationManager
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.createDeviceProtectedStorageContext().getSharedPreferences("alarm-v1", 0).edit().clear().commit()
        store = SettingsStore(app)
        notifications = app.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(AlarmNotifications.vibrator(app)).setHasVibrator(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    private fun ringing(): AlarmPlan {
        val p = AlarmController(app).armTest(AlarmSettings()).copy(
            ringNonce = "ring-1", ringKind = RingKind.FINAL, finalDelivered = true,
        )
        store.savePlan(p)
        return p
    }
    private fun verifyPromotionFailure(type: Class<out RingingService>) {
        val p = ringing()
        val service = Robolectric.buildService(type).create()
        assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(Intent(), 0, 1))
        assertTrue(store.alarmError().contains("전경 실행"))
        assertNotNull(shadowOf(notifications).getNotification(AlarmNotifications.FALLBACK_ID))
        assertEquals(p.ringNonce, store.plan()!!.ringNonce)
        service.destroy()
        // Cleanup of the failed service must not cancel its independently posted fallback.
        assertNotNull(shadowOf(notifications).getNotification(AlarmNotifications.FALLBACK_ID))
        assertTrue(shadowOf(AlarmNotifications.vibrator(app)).isVibrating)
    }
    @Test fun `security exception during startForeground is handled inside service`() {
        verifyPromotionFailure(SecurityDeniedPromotionService::class.java)
    }
    @Test fun `illegal state during foreground promotion does not crash or restart loop`() {
        verifyPromotionFailure(StateDeniedPromotionService::class.java)
    }
    @Test fun `caller startForegroundService denial preserves final reservation and reports fallback`() {
        val blocked = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw SecurityException("background service denied")
            }
        }
        val controller = AlarmController(blocked)
        val deadline = System.currentTimeMillis() + 60_000
        val p = controller.arm(AlarmSettings(), deadline)
        assertTrue(controller.smartWake(p.id))
        assertEquals(deadline, app.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        assertTrue(store.alarmError().contains("서비스 시작"))
        assertNotNull(shadowOf(notifications).getNotification(AlarmNotifications.FALLBACK_ID))
    }
    @Test fun `notification denial still attempts vibration and exposes limitations`() {
        val p = ringing()
        shadowOf(notifications).setNotificationsEnabled(false)
        val result = AlarmNotifications.fallback(app, p)
        assertFalse(result.notificationPosted)
        assertTrue(result.vibrationStarted)
        assertTrue(result.limitations.any { it.contains("차단") })
        assertTrue(shadowOf(AlarmNotifications.vibrator(app)).isVibrating)
    }
    @Test fun `null service start result is surfaced instead of silently ignored`() {
        val unavailable = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? = null
        }
        val controller = AlarmController(unavailable)
        val p = controller.arm(AlarmSettings(), System.currentTimeMillis() + 60_000)
        assertTrue(controller.smartWake(p.id))
        assertTrue(store.alarmError().contains("서비스 시작"))
        assertNotNull(shadowOf(notifications).getNotification(AlarmNotifications.FALLBACK_ID))
    }
    @Test fun `both fallback paths unavailable are persisted rather than silently ignored`() {
        val p = ringing()
        shadowOf(notifications).setNotificationsEnabled(false)
        shadowOf(AlarmNotifications.vibrator(app)).setHasVibrator(false)
        AlarmFailureReporter.report(app, p, "알람 서비스 시작", IllegalStateException("denied"))
        assertTrue(store.alarmError().contains("대체 알림 실패"))
        assertTrue(store.alarmError().contains("진동 실패"))
        assertTrue(store.alarmError().contains("진동 장치 없음"))
    }
    @Test fun `blocked fallback channel does not block direct vibration`() {
        val p = ringing()
        notifications.createNotificationChannel(NotificationChannel(AlarmNotifications.FALLBACK_CHANNEL, "blocked", NotificationManager.IMPORTANCE_NONE))
        val result = AlarmNotifications.fallback(app, p)
        assertFalse(result.notificationPosted)
        assertTrue(result.vibrationStarted)
    }
    @Test fun `dismiss and snooze remove fallback notifications with valid nonce`() {
        var p = ringing()
        AlarmNotifications.fallback(app, p)
        AlarmController(app).snooze(p.ringNonce)
        assertNull(shadowOf(notifications).getNotification(AlarmNotifications.FALLBACK_ID))
        assertFalse(shadowOf(AlarmNotifications.vibrator(app)).isVibrating)
        p = ringing()
        AlarmNotifications.fallback(app, p)
        AlarmController(app).dismiss(p.ringNonce)
        assertNull(shadowOf(notifications).getNotification(AlarmNotifications.FALLBACK_ID))
        assertFalse(shadowOf(AlarmNotifications.vibrator(app)).isVibrating)
    }
    @Test fun `sensor updates cannot hide an alarm failure`() {
        store.alarmError("foreground failed")
        store.status("sensor registered")
        assertEquals("foreground failed", SettingsStore(app).alarmError())
    }
}
