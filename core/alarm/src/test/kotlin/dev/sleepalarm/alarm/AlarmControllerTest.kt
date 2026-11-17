package dev.sleepalarm.alarm

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import dev.sleepalarm.model.AlarmSettings
import dev.sleepalarm.settings.SettingsStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config

/** JVM framework simulation, not a real watch or a guarantee of hardware delivery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmControllerTest {
    private lateinit var app: Application
    private lateinit var manager: AlarmManager
    private lateinit var controller: AlarmController
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.createDeviceProtectedStorageContext().getSharedPreferences("alarm-v1", 0).edit().clear().commit()
        manager = app.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller = AlarmController(app)
    }
    @Test fun `final is an alarm clock even with smart sensors disabled`() {
        val at = System.currentTimeMillis() + 3_600_000
        val p = controller.arm(AlarmSettings(smartEnabled = false), at)
        assertEquals(at, manager.nextAlarmClock.triggerTime)
        assertEquals(p.id, SettingsStore(app).plan()!!.id)
    }
    @Test fun `smart ringing leaves final alarm scheduled`() {
        val at = System.currentTimeMillis() + 60_000
        val p = controller.arm(AlarmSettings(), at)
        assertTrue(controller.smartWake(p.id))
        assertEquals(at, manager.nextAlarmClock.triggerTime)
        assertTrue(SettingsStore(app).plan()!!.ringing)
        assertFalse(SettingsStore(app).plan()!!.finalDelivered)
    }
    @Test fun `restore receiver recreates pending alarm from persistent watch state`() {
        val at = System.currentTimeMillis() + 3_600_000
        controller.arm(AlarmSettings(smartEnabled = false), at)
        manager.cancelAll()
        assertNull(manager.nextAlarmClock)
        RestoreReceiver().onReceive(app, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(at, manager.nextAlarmClock.triggerTime)
    }
    @Test fun `revoked exact permission cannot replace an existing reservation`() {
        val p = controller.arm(AlarmSettings(smartEnabled = false), System.currentTimeMillis() + 3_600_000)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThrows(IllegalStateException::class.java) { controller.arm(AlarmSettings()) }
        assertEquals(p.id, SettingsStore(app).plan()!!.id)
    }
    @Test fun `dismiss removes final reservation and stale old session cannot ring`() {
        val p = controller.arm(AlarmSettings(smartEnabled = false), System.currentTimeMillis() + 3_600_000)
        controller.dismiss()
        assertNull(manager.nextAlarmClock)
        assertFalse(controller.smartWake(p.id))
        assertFalse(SettingsStore(app).plan()!!.enabled)
    }

    @Test fun `test snapshot never overwrites any saved preferences even after reopening store`() {
        val saved = AlarmSettings(hour = 9, minute = 17, windowMinutes = 10, smartEnabled = true,
            amplitude = 83, pattern = dev.sleepalarm.model.VibrationPattern.PULSE, snoozeMinutes = 13)
        SettingsStore(app).saveSettings(saved)
        val test = controller.armTest(saved.copy(hour = 12, amplitude = 200))
        assertFalse(test.settings.smartEnabled)
        assertEquals(200, test.settings.amplitude)
        assertEquals(saved, SettingsStore(app).settings())
        assertEquals(test.deadline, manager.nextAlarmClock.triggerTime)
        manager.cancelAll()
        controller.restore()
        assertEquals(saved, SettingsStore(app).settings())
        assertFalse(SettingsStore(app).plan()!!.settings.smartEnabled)
        controller.dismiss()
        assertEquals(saved, SettingsStore(app).settings())
        assertTrue(controller.arm(SettingsStore(app).settings()).settings.smartEnabled)
    }

    @Test fun `failed test reservation also leaves saved settings unchanged`() {
        val saved = AlarmSettings(smartEnabled = false, windowMinutes = 60)
        SettingsStore(app).saveSettings(saved)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThrows(IllegalStateException::class.java) { controller.armTest(AlarmSettings()) }
        assertEquals(saved, SettingsStore(app).settings())
        assertNull(SettingsStore(app).plan())
    }
}
