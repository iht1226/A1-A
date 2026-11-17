package dev.sleepalarm.sensors

import android.Manifest
import android.app.*
import android.content.*
import android.database.sqlite.SQLiteDatabase
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.*
import dev.sleepalarm.diagnostics.DiagnosticArchive
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CaptureStorageTest {
    private lateinit var app: Application
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication()
        app.deleteDatabase("observations-v1.db"); app.deleteDatabase("sensor-diagnostics-v1.db")
        app.createDeviceProtectedStorageContext().getSharedPreferences("alarm-v1",0).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
    @Test fun `v2 migration adds raw event audit without rewriting observations`() {
        val db=SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE motion(session TEXT, at INTEGER, energy REAL, covered INTEGER, received INTEGER, PRIMARY KEY(session,at))")
        db.execSQL("CREATE TABLE heart(session TEXT, at INTEGER, bpm REAL, quality REAL, received INTEGER, PRIMARY KEY(session,at))")
        db.execSQL("INSERT INTO motion VALUES('n',5000,0.03,4800,9000)")
        SensorDatabase(app).use { it.onUpgrade(db,2,3) }
        db.rawQuery("SELECT received FROM motion",null).use { assertTrue(it.moveToFirst()); assertEquals(9000L,it.getLong(0)) }
        db.rawQuery("SELECT name FROM sqlite_master WHERE name='audit'",null).use { assertTrue(it.moveToFirst()) }; db.close()
    }
    @Test fun `restart duplicate bins preserve first availability for causal replay`() {
        SensorDatabase(app).use { db ->
            val first=MotionSample(5000,0.03,4000,15_000)
            assertTrue(db.addMotion("n",first))
            assertFalse(db.addMotion("n",first.copy(coveredMillis=5000,receivedAt=150_000)))
            assertEquals(listOf(first),db.motion("n",0))
        }
        SensorDatabase(app).use { assertEquals(15_000L,it.motion("n",0).single().receivedAt) }
    }
    @Test fun `audit preserves actual measurement arrival and discard reason including long delays`() {
        SensorDatabase(app).use { db ->
            db.audit("n",listOf(SensorDatabase.Audit(50_000_000_000,50_000,200_000,0.03,3,"ACCEPTED"),
                SensorDatabase.Audit(51_000_000_000,51_000,200_000,null,0,"UNRELIABLE_ACCURACY")))
            db.readableDatabase.rawQuery("SELECT measured, received, reason FROM audit WHERE session='n' ORDER BY nanos",null).use {
                assertTrue(it.moveToFirst()); assertEquals(50_000L,it.getLong(0)); assertEquals(200_000L,it.getLong(1)); assertEquals("ACCEPTED",it.getString(2))
                assertTrue(it.moveToNext()); assertEquals("UNRELIABLE_ACCURACY",it.getString(2))
            }
        }
    }
    @Test fun `test permission rejection leaves settings reports and final alarm untouched`() {
        val store=SettingsStore(app)
        val p=AlarmController(app).arm(AlarmSettings(),System.currentTimeMillis()+3*3_600_000)
        val before=store.reports(); val settings=store.settings()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION,Manifest.permission.BODY_SENSORS,Manifest.permission.BODY_SENSORS_BACKGROUND)
        val service=Robolectric.buildService(SmartWindowService::class.java).create()
        assertEquals(Service.START_NOT_STICKY,service.get().onStartCommand(Intent(DiagnosticArchive.TEST_START).putExtra("minutes",10),0,1))
        assertEquals(p,store.plan()); assertEquals(settings,store.settings()); assertEquals(before,store.reports())
        assertEquals(p.deadline,app.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        service.destroy()
    }
    @Test fun `collection trial is rejected near alarm measurement without replacing reservation`() {
        val store=SettingsStore(app); val p=AlarmController(app).arm(AlarmSettings(),System.currentTimeMillis()+45*60_000)
        val service=Robolectric.buildService(SmartWindowService::class.java).create()
        assertEquals(Service.START_NOT_STICKY,service.get().onStartCommand(Intent(DiagnosticArchive.TEST_START),0,1))
        assertEquals(p,store.plan()); assertEquals(p.deadline,app.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        service.destroy()
    }
}
