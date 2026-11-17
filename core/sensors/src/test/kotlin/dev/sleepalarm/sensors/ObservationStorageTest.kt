package dev.sleepalarm.sensors

import android.Manifest
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import dev.sleepalarm.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservationStorageTest {
    @Test fun `v1 upgrade preserves observations but marks unknown arrival times unavailable for causal replay`() {
        val app = RuntimeEnvironment.getApplication()
        val db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE motion(session TEXT, at INTEGER, energy REAL, covered INTEGER, PRIMARY KEY(session, at))")
        db.execSQL("CREATE TABLE heart(session TEXT, at INTEGER, bpm REAL, PRIMARY KEY(session, at))")
        db.execSQL("INSERT INTO motion VALUES('n', 5000, 0.02, 4500)")
        db.execSQL("INSERT INTO heart VALUES('n', 5000, 60)")
        SensorDatabase(app).use { it.onUpgrade(db, 1, 2) }
        db.rawQuery("SELECT received FROM motion", null).use { it.moveToFirst(); assertEquals(Long.MAX_VALUE, it.getLong(0)) }
        db.rawQuery("SELECT bpm, quality, received FROM heart", null).use {
            it.moveToFirst(); assertEquals(60.0, it.getDouble(0), 0.0)
            assertEquals(1.0, it.getDouble(1), 0.0); assertEquals(Long.MAX_VALUE, it.getLong(2))
        }
        db.close()
    }
    @Test fun `quality and actual arrival times round trip without sample count inflation`() {
        val app = RuntimeEnvironment.getApplication()
        SensorDatabase(app).use { db ->
            val m = MotionSample(5000, 0.05, 4600, 12_000)
            val h = HeartSample(6000, 62.0, 0.25, 30_000)
            db.addMotion("n", m); db.addMotion("n", m)
            db.addHeart("n", listOf(h,h))
            assertEquals(listOf(m), db.motion("n", 0))
            assertEquals(listOf(h), db.heart("n", 0))
        }
    }
    @Test fun `motion monitoring is allowed without heart permissions when activity recognition is granted`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.BODY_SENSORS, Manifest.permission.BODY_SENSORS_BACKGROUND)
        shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertFalse(PassiveHeartMonitor.permitted(app))
        assertTrue(MonitoringAccess.canMonitor(app))
        shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        assertFalse(MonitoringAccess.canMonitor(app))
    }
}
