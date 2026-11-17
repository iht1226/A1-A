package dev.sleepalarm.alarm

import android.app.*
import android.content.*
import android.provider.Settings
import dev.sleepalarm.model.AlarmSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmAccessTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.createDeviceProtectedStorageContext().getSharedPreferences("alarm-v1", 0).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @Test fun `notification denial is separate and does not prevent independent exact reservation`() {
        Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val access = AlarmAccess.inspect(app)
        assertTrue(access.exact); assertFalse(access.notifications)
        val p = AlarmController(app).armTest(AlarmSettings())
        assertEquals(p.deadline, app.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        assertTrue(access.text().contains("예약 가능")); assertTrue(access.text().contains("앱 알림 설정: 꺼짐"))
    }
    @Test fun `enabled notification does not masquerade as exact permission`() {
        Shadows.shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val access = AlarmAccess.inspect(app)
        assertTrue(access.notifications); assertFalse(access.exact)
        assertThrows(IllegalStateException::class.java) { AlarmController(app).armTest(AlarmSettings()) }
    }
    @Test fun `missing special screen falls back to app details`() {
        val seen = mutableListOf<String?>()
        val c = object : ContextWrapper(app) {
            override fun startActivity(i: Intent) { seen += i.action; if (i.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw ActivityNotFoundException() }
        }
        assertTrue(SafeSettings.open(c, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).contains("대체"))
        assertEquals(listOf(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Settings.ACTION_APPLICATION_DETAILS_SETTINGS), seen)
    }
    @Test fun `missing or denied all settings screens returns manual guidance without exception`() {
        val c = object : ContextWrapper(app) { override fun startActivity(i: Intent) { throw SecurityException("OEM restricted") } }
        assertTrue(SafeSettings.open(c, Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).contains("직접 확인"))
    }
    @Test fun `blocked alarm channel is diagnosed independently`() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("wake-v1", "wake", NotificationManager.IMPORTANCE_NONE))
        val a = AlarmAccess.inspect(app)
        assertTrue(a.exact); assertFalse(a.channelEnabled)
    }
}
