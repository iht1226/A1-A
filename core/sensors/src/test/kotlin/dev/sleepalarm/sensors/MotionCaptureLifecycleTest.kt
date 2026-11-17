package dev.sleepalarm.sensors

import android.app.*
import android.hardware.*
import android.os.*
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.model.AlarmSettings
import dev.sleepalarm.diagnostics.DiagnosticArchive
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MotionCaptureLifecycleTest {
    private lateinit var app: Application
    private lateinit var sensor: Sensor
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication()
        app.deleteDatabase("observations-v1.db"); app.deleteDatabase("sensor-diagnostics-v1.db")
        app.createDeviceProtectedStorageContext().getSharedPreferences("alarm-v1",0).edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        sensor=ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER)
        Shadows.shadowOf(app.getSystemService(SensorManager::class.java)).addSensor(sensor)
    }
    private fun serial(c: MotionCapture, block: () -> Unit) {
        val done=CountDownLatch(1); val error=AtomicReference<Throwable?>()
        c.handler.post { try { block() } catch(t:Throwable) { error.set(t) } finally { done.countDown() } }
        assertTrue("capture thread timed out",done.await(10,TimeUnit.SECONDS)); error.get()?.let { throw AssertionError(it) }
    }
    private fun close(c: MotionCapture) { c.close("TEST_END"); if(c.handler.looper.thread.isAlive) serial(c) {} }
    @Test fun `silent stream recovery is bounded and a service restart preserves final alarm and session diagnostics`() {
        val p=AlarmController(app).arm(AlarmSettings(),System.currentTimeMillis()+3*3_600_000)
        val now=System.currentTimeMillis()
        val spec=CaptureSpec("collection",now-1000,now+300_000,now+900_000,true)
        val c=MotionCapture(app,spec,{_,_->},{})
        try {
            c.start(); serial(c) {
                assertEquals(1,c.state.getInt("generation"))
                c.health(now+61_000); c.health(now+62_000)
                assertEquals(1,c.state.getInt("reRegistrations"))
                c.persist(now+62_000)
            }
        } finally { close(c) }
        val resumed=MotionCapture(app,spec,{_,_->},{})
        try { resumed.start(); serial(resumed) { assertEquals(2,resumed.state.getInt("generation")); assertEquals(0,resumed.db.motion(spec.id,0).size) } }
        finally { close(resumed) }
        assertEquals(p.deadline,app.getSystemService(AlarmManager::class.java).nextAlarmClock.triggerTime)
        DiagnosticArchive(app).use { a -> assertTrue(a.events(spec.id).toString().contains("SERVICE_RESTART")) }
    }
    @Test fun `delayed raw events keep measurement and receipt time and invalid future cannot poison later events`() {
        Shadows.shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(5))
        val now=System.currentTimeMillis()
        val c=MotionCapture(app,CaptureSpec("delayed",now-180_000,now+300_000,now+900_000,true),{_,_->},{})
        val base=SystemClock.elapsedRealtimeNanos()-120_000_000_000L
        fun event(timestamp:Long,accuracy:Int=3):SensorEvent {
            val ctor=SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible=true }
            return ctor.newInstance(3).apply { this.timestamp=timestamp; this.accuracy=accuracy; this.sensor=this@MotionCaptureLifecycleTest.sensor; values[2]=SensorManager.GRAVITY_EARTH }
        }
        try { c.start(); serial(c) {
            c.onSensorChanged(event(base)); c.onSensorChanged(event(base+200_000_000))
            c.onSensorChanged(event(base+200_000_000)); c.onSensorChanged(event(base+400_000_000,0))
            c.onSensorChanged(event(SystemClock.elapsedRealtimeNanos()+10_000_000_000))
            c.onSensorChanged(event(base+600_000_000))
            assertEquals(3,c.state.getInt("accepted")); assertTrue(c.state.getLong("maxDelay")>60_000)
            assertEquals(1,c.state.getJSONObject("discard").getInt("DUPLICATE_OR_REORDERED"))
            assertEquals(1,c.state.getJSONObject("discard").getInt("UNRELIABLE_ACCURACY"))
            assertEquals(1,c.state.getJSONObject("discard").getInt("FUTURE_TIMESTAMP"))
        } } finally { close(c) }
        SensorDatabase(app).use { db -> db.readableDatabase.rawQuery("SELECT measured,received FROM audit WHERE reason='ACCEPTED' LIMIT 1",null).use {
            assertTrue(it.moveToFirst()); assertTrue(it.getLong(1)-it.getLong(0)>60_000)
        } }
    }
}
