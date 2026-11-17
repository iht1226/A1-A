package dev.sleepalarm.alarm

import android.app.*
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptiveControllerTest {
    private lateinit var app: Application
    private lateinit var store: SettingsStore
    private lateinit var controller: AlarmController
    private lateinit var manager: AlarmManager
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        app.createDeviceProtectedStorageContext().getSharedPreferences("alarm-v1", 0).edit().clear().commit()
        store = SettingsStore(app); controller = AlarmController(app)
        manager = app.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    private fun day() = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
        .plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `early acknowledgement can retain the independent final and explicit cancel removes it`() {
        val deadline = System.currentTimeMillis() + 60_000
        val p = controller.arm(AlarmSettings(keepFinalAfterSmartDismiss = true), deadline)
        controller.smartWake(p.id)
        controller.dismiss(store.plan()!!.ringNonce)
        assertTrue(store.plan()!!.enabled)
        assertFalse(store.plan()!!.ringing)
        assertEquals(deadline, manager.nextAlarmClock.triggerTime)
        controller.restore()
        assertEquals(deadline, manager.nextAlarmClock.triggerTime)
        controller.dismiss()
        assertNull(manager.nextAlarmClock)
    }
    @Test fun `early snooze reaching deadline does not reserve a later or duplicate alarm`() {
        val deadline = System.currentTimeMillis() + 60_000
        val p = controller.arm(AlarmSettings(snoozeMinutes = 30), deadline)
        controller.smartWake(p.id)
        controller.snooze(store.plan()!!.ringNonce)
        assertEquals(deadline, store.plan()!!.snoozeAt)
        assertEquals(deadline, manager.nextAlarmClock.triggerTime)
        assertTrue(Shadows.shadowOf(manager).scheduledAlarms.none { Shadows.shadowOf(it.operation).savedIntent.action == AlarmController.SNOOZE })
        controller.restore()
        assertEquals(deadline, manager.nextAlarmClock.triggerTime)
        assertTrue(Shadows.shadowOf(manager).scheduledAlarms.none { Shadows.shadowOf(it.operation).savedIntent.action == AlarmController.SNOOZE })
    }
    @Test fun `alternating trial is frozen for rebooking reboot and next day`() {
        val settings = AlarmSettings(algorithmMode = AlgorithmMode.ALTERNATE)
        val first = controller.arm(settings, day())
        assertEquals(AlgorithmId.ADAPTIVE_020, first.algorithm)
        controller.restore()
        assertEquals(first.algorithm, store.plan()!!.algorithm)
        assertEquals(first.algorithm, controller.arm(settings, day() + 60_000).algorithm)
        assertEquals(AlgorithmId.LEGACY_011, controller.arm(settings, day() + 86_400_000).algorithm)
    }
    @Test fun `random trial is not redrawn on same date or restoration`() {
        val settings = AlarmSettings(algorithmMode = AlgorithmMode.RANDOM)
        val first = controller.arm(settings, day())
        assertEquals(first.algorithm, controller.arm(settings, day() + 60_000).algorithm)
        controller.restore(); assertEquals(first.algorithm, store.plan()!!.algorithm)
    }
    @Test fun `test alarm does not consume the next trial assignment`() {
        val settings = AlarmSettings(algorithmMode = AlgorithmMode.ALTERNATE)
        controller.arm(settings, day())
        val test = controller.armTest(settings)
        assertTrue(store.reports().first { it.id == test.id }.details.testAlarm)
        assertEquals(AlgorithmId.LEGACY_011, controller.arm(settings, day() + 86_400_000).algorithm)
    }
    @Test fun `old settings and armed plans migrate without changing an armed legacy algorithm`() {
        val j = JsonCodec.settings(AlarmSettings()).apply { remove("keepFinal"); remove("algorithmMode"); remove("threshold") }
        assertEquals(AlgorithmMode.ADAPTIVE, JsonCodec.settings(j).algorithmMode)
        assertFalse(JsonCodec.settings(j).keepFinalAfterSmartDismiss)
        val p = controller.arm(AlarmSettings(), day())
        assertEquals(AlgorithmId.LEGACY_011, JsonCodec.plan(JsonCodec.plan(p).apply { remove("algorithm") }).algorithm)
    }
    @Test fun `new reports round trip and older updates cannot erase phone annotations`() {
        val now = System.currentTimeMillis()
        val report = NightReport("x", now - 2_000, now - 1000, firstRingAt = now - 1_000,
            details = NightDetails(algorithm = AlgorithmId.ADAPTIVE_020, smartScheduled = true,
                events = listOf(AlarmEvent(now, "SMART", "OUTPUT_API_STARTED")), profileSnapshot = "{}"))
        assertEquals(report, JsonCodec.report(JsonCodec.report(report)))
        store.putReport(report)
        store.feedback("x", 4, 2, now)
        store.updateReport("x") { it.copy(details = it.details.copy(healthSleepMinutes = 420, healthComparedAt = now)) }
        store.putReport(report.copy(motionBins = 10))
        val merged = store.reports().first { it.id == "x" }
        assertEquals(4, merged.details.refreshed)
        assertEquals(420L, merged.details.healthSleepMinutes)
        assertEquals(10, merged.motionBins)
    }
    @Test fun `ordinary final alarm needs neither phone connectivity nor sensor service`() {
        // This module's test app has no Wearable Data Layer or sensor services.
        val p = controller.arm(AlarmSettings(), day())
        assertEquals(p.deadline, manager.nextAlarmClock.triggerTime)
        assertTrue(store.plan()!!.enabled)
    }
}
