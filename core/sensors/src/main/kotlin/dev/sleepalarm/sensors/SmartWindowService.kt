package dev.sleepalarm.sensors

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.HealthServices
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.analysis.*
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.SettingsStore
import dev.sleepalarm.settings.JsonCodec
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

class SmartWindowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session = ""
    private var db: SensorDatabase? = null
    private var listener: SensorEventListener? = null
    private var sensorThread: HandlerThread? = null
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    override fun onBind(intent: Intent?) = null
    private fun battery(): Int = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        val p = store.plan()
        val now = System.currentTimeMillis()
        if (p == null || !p.enabled || !p.settings.smartEnabled || p.finalDelivered || p.earlyDelivered ||
            now !in p.sensorStart until p.deadline || !MonitoringAccess.canMonitor(this) || store.blockedSession() == p.id) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("monitor-v1", "기상 범위 센서 분석", NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, 701,
                NotificationCompat.Builder(this, "monitor-v1").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("기상 관측 · 기준선/움직임 분석")
                    .setContentText("수면 단계 분류가 아닙니다. 정시 알람은 독립 예약되어 있습니다.")
                    .setOngoing(true).build(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        } catch (e: Exception) { store.status("센서 전경 실행 제한: 정시 알람 유지 (${e.javaClass.simpleName})"); stopSelf(); return START_NOT_STICKY }
        if (session == p.id) return START_NOT_STICKY
        session = ""
        stopCapture()
        session = p.id
        val observations = try { SensorDatabase(this).also { it.prune(now) } }
        catch (e: Exception) { store.status("관측 DB 오류: 정시 알람 유지"); stopSelf(); return START_NOT_STICKY }
        db = observations
        val monitorOffset = now - SystemClock.elapsedRealtime()
        val accumulator = MotionAccumulator()
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) { store.status("가속도 센서 없음: 정시 알람 유지"); stopSelf(); return START_NOT_STICKY }
        sensorThread = HandlerThread("sleep-motion").also { it.start() }
        val callback = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val at = monitorOffset + event.timestamp / 1_000_000
                val received = System.currentTimeMillis()
                if (at < p.armedAt || at > received || received - at > 60_000 || event.values.size < 3 || event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
                val energy = abs(sqrt(event.values.take(3).sumOf { it.toDouble().pow(2) }) - SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH
                accumulator.add(at, energy)?.takeIf { it.coveredMillis > 0 }?.let {
                    runCatching { observations.addMotion(p.id, it.copy(receivedAt = received)) }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = callback
        val registered = runCatching { sensors.registerListener(callback, accelerometer, 200_000, 10_000_000, Handler(sensorThread!!.looper)) }.getOrDefault(false)
        if (!registered) { store.status("움직임 센서 등록 실패: 정시 알람 유지"); stopSelf(); return START_NOT_STICKY }
        val context = store.sleepContext().takeIf { it.recordedAt <= p.armedAt } ?: SleepContext()
        val reference = Personalization.reference(store.reports(), p.armedAt, context)
        val profileJson = JSONObject().put("version", "adaptive-0.2.0").put("parameters", AdaptiveParameters().toString())
            .put("userThreshold", p.settings.adaptiveThreshold).put("motionMedians", JSONArray(reference.motionMedians))
            .put("motionMads", JSONArray(reference.motionMads)).put("offset", reference.thresholdOffset)
            .put("feedbackNights", reference.feedbackNights).put("sleepContext", JsonCodec.context(context)).toString()
        runCatching { store.updateReport(p.id) { it.copy(details = it.details.copy(
            batteryStart = if (it.details.batteryStartedAt == 0L) battery() else it.details.batteryStart,
            batteryStartedAt = it.details.batteryStartedAt.takeIf { at -> at > 0 } ?: now,
            personalizationOffset = reference.thresholdOffset, personalizedNights = reference.feedbackNights,
            profileSnapshot = profileJson)) } }
        if (PassiveHeartMonitor.permitted(this)) scope.launch {
            runCatching { withTimeout(15_000) { PassiveHeartMonitor.register(this@SmartWindowService) } }
                .onFailure { store.status("심박 등록 지연/제한: 움직임 기반 분석 유지") }
        } else store.status("심박 없이 움직임 기준선 수집 중")
        scope.launch {
            val comparison = CausalComparison(AdaptiveWakeEstimator(reference = reference, userThreshold = p.settings.adaptiveThreshold))
            try {
                while (isActive) {
                    delay(WakeTiming.EVALUATION_MILLIS)
                    val current = store.plan()
                    val time = System.currentTimeMillis()
                    if (current?.id != p.id || !current.enabled || current.finalDelivered || current.earlyDelivered || time >= current.deadline) break
                    if (!MonitoringAccess.canMonitor(this@SmartWindowService) || store.blockedSession() == p.id) break
                    if (abs((time - SystemClock.elapsedRealtime()) - monitorOffset) > 2_000) {
                        store.blockSmart(p.id); store.status("시스템 시각 변경: 조기 분석 중지, 정시 알람 유지"); break
                    }
                    if (time < p.windowStart) { store.status("개인 기준선 예열 중 · 스마트 판단은 설정 범위에서 시작"); continue }
                    val result = runCatching {
                        comparison.update(time, SystemClock.elapsedRealtime(), p.windowStart, p.deadline,
                            observations.motion(p.id, p.sensorStart),
                            if (PassiveHeartMonitor.permitted(this@SmartWindowService)) observations.heart(p.id, p.sensorStart) else emptyList())
                    }.getOrElse { store.status("분석 오류: 이번 관측 보류, 정시 알람 유지"); continue }
                    val selected = if (p.algorithm == AlgorithmId.LEGACY_011) result.legacy else result.adaptive
                    store.status(selected.reason)
                    val evidence = result.adaptive.evidence
                    runCatching { store.updateReport(p.id) { it.copy(
                        motionBins = observations.motion(p.id, p.sensorStart).size,
                        heartSamples = observations.heart(p.id, p.sensorStart).size, decision = selected.reason,
                        details = it.details.copy(observationEnd = time, legacySuggestedAt = result.legacyAt,
                            adaptiveSuggestedAt = result.adaptiveAt, legacyReason = result.legacy.reason,
                            legacyConfidence = result.legacy.confidence, adaptiveConfidence = result.adaptive.confidence,
                            adaptiveReason = result.adaptive.reason, motionQuality = evidence?.motionQuality ?: 0.0,
                            heartQuality = evidence?.heartQuality ?: 0.0, referenceMedian = evidence?.motionMedian ?: 0.0,
                            referenceMad = evidence?.motionMad ?: 0.0, referenceHeart = evidence?.heartMedian ?: 0.0,
                            referenceHeartMad = evidence?.heartMad ?: 0.0)) } }
                    val chosenAt = if (p.algorithm == AlgorithmId.LEGACY_011) result.legacyAt else result.adaptiveAt
                    if (chosenAt > 0) { ensureActive(); AlarmController(this@SmartWindowService).smartWake(p.id); break }
                }
            } finally { if (session == p.id) stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun stopCapture() {
        listener?.let { sensors.unregisterListener(it) }; listener = null
        sensorThread?.quitSafely(); sensorThread = null
        scope.coroutineContext.cancelChildren()
        runCatching { db?.close() }; db = null
    }
    override fun onDestroy() {
        val id = session
        session = ""
        stopCapture()
        runCatching { SettingsStore(this).updateReport(id) { it.copy(details = it.details.copy(batteryEnd = battery())) } }
        sendBroadcast(Intent(AlarmController.ACTION_REPORT_UPDATED).setPackage(packageName))
        runCatching { HealthServices.getClient(this).passiveMonitoringClient.clearPassiveListenerServiceAsync() }
        scope.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
