package dev.sleepalarm.sensors

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.analysis.*
import dev.sleepalarm.settings.SettingsStore
import kotlinx.coroutines.*
import kotlin.math.*

class SmartWindowService : Service(), SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private lateinit var db: SensorDatabase
    private lateinit var sensors: SensorManager
    private var session = ""
    private var offset = 0L
    private var accumulator = MotionAccumulator()
    private var sensorThread: HandlerThread? = null
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        val p = store.plan()
        if (p == null || !p.enabled || !p.settings.smartEnabled || p.finalDelivered || p.earlyDelivered ||
            System.currentTimeMillis() !in p.windowStart until p.deadline ||
            !PassiveHeartMonitor.permitted(this) || store.blockedSession() == p.id) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("monitor-v1", "기상 범위 센서 분석", NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, 701,
                NotificationCompat.Builder(this, "monitor-v1").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("기상 시점 관측 중").setContentText("수면 단계 판정이 아닙니다. 일반 알람은 별도 예약되어 있습니다.")
                    .setOngoing(true).build(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        } catch (e: Exception) { store.status("센서 실행 권한 제한: 일반 알람 유지"); stopSelf(); return START_NOT_STICKY }
        if (started && session == p.id) return START_NOT_STICKY
        if (started) {
            sensors.unregisterListener(this)
            sensorThread?.quitSafely()
            scope.coroutineContext.cancelChildren()
            db.close()
            accumulator = MotionAccumulator()
        }
        started = true
        session = p.id
        offset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        db = SensorDatabase(this)
        db.prune(System.currentTimeMillis())
        sensors = getSystemService(SensorManager::class.java)
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) { store.status("가속도 센서 없음: 일반 알람 유지"); stopSelf(); return START_NOT_STICKY }
        sensorThread = HandlerThread("sleep-motion").also { it.start() }
        val registered = runCatching { sensors.registerListener(this, accelerometer, 200_000, 10_000_000, Handler(sensorThread!!.looper)) }.getOrDefault(false)
        if (!registered) { store.status("가속도 센서 등록 실패: 일반 알람 유지"); stopSelf(); return START_NOT_STICKY }
        val monitorId = p.id
        val observations = db
        val monitorOffset = offset
        scope.launch {
            val confirmation = SustainedWakeGate()
            runCatching { withTimeout(15_000) { PassiveHeartMonitor.register(this@SmartWindowService) } }
            val estimator = ConservativeWakeEstimator()
            while (isActive) {
                delay(WakeTiming.EVALUATION_MILLIS)
                val current = store.plan()
                val now = System.currentTimeMillis()
                if (current?.id != monitorId || !current.enabled || current.finalDelivered || current.earlyDelivered || now >= current.deadline) break
                if (!PassiveHeartMonitor.permitted(this@SmartWindowService) || store.blockedSession() == monitorId) {
                    store.status("센서 접근 제한: 조기 판단 중지, 일반 알람 유지"); break
                }
                if (abs((now - SystemClock.elapsedRealtime()) - monitorOffset) > 2_000) {
                    store.blockSmart(monitorId); store.status("시스템 시각 변경: 이번 스마트 분석 중지, 일반 알람 유지"); break
                }
                val decision = runCatching {
                    estimator.evaluate(now, current.windowStart, current.deadline, observations.motion(monitorId, now - 10 * 60_000), observations.heart(monitorId, now - 10 * 60_000))
                }.getOrElse { dev.sleepalarm.model.WakeDecision(false, 0.0, "센서 데이터 읽기 실패") }
                store.status(decision.reason)
                runCatching { store.updateReport(monitorId) { it.copy(
                    motionBins = observations.motion(monitorId, current.armedAt).size,
                    heartSamples = observations.heart(monitorId, current.armedAt).size,
                    decision = decision.reason,
                ) } }
                if (confirmation.update(decision.wake, SystemClock.elapsedRealtime())) {
                    ensureActive()
                    AlarmController(this@SmartWindowService).smartWake(monitorId)
                    break
                }
            }
            if (session == monitorId) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Hardware timestamps are elapsedRealtimeNanos, not wall-clock arrival times.
        val at = offset + event.timestamp / 1_000_000
        val now = System.currentTimeMillis()
        if (at > now || now - at > 60_000 || event.values.size < 3 || event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
        val energy = abs(sqrt(event.values.take(3).sumOf { it.toDouble().pow(2) }) - SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH
        accumulator.add(at, energy)?.takeIf { it.coveredMillis > 0 }?.let {
            runCatching { db.addMotion(session, it) }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onDestroy() {
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        sensorThread?.quitSafely()
        scope.cancel()
        if (::db.isInitialized) db.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
