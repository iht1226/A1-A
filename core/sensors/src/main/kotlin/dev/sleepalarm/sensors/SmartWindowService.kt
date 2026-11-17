package dev.sleepalarm.sensors

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.analysis.*
import dev.sleepalarm.diagnostics.DiagnosticArchive
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.*
import java.util.UUID

class SmartWindowService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var capture: MotionCapture? = null
    private var endReason = "SERVICE_DESTROYED_OR_SYSTEM_STOP"
    private val stopTest = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (capture?.spec?.test == true) { endReason = "TEST_USER_STOP"; stopSelf() }
        }
    }
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, stopTest, IntentFilter(DiagnosticArchive.TEST_STOP), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?) = null
    private fun battery(): Int = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = try { start(intent, startId) }
        catch (e: Exception) { SettingsStore(this).status("센서 시작 오류: ${e.javaClass.simpleName}; 정시 알람 유지"); stopSelf(); START_NOT_STICKY }
    private fun start(intent: Intent?, startId: Int): Int {
        val now = System.currentTimeMillis(); val store = SettingsStore(this); val p = store.plan()
        val activePlan = p?.takeIf { it.enabled && it.settings.smartEnabled && !it.finalDelivered && !it.earlyDelivered &&
            now in it.sensorStart until it.deadline && store.blockedSession() != it.id }
        val testRequest = intent?.action == DiagnosticArchive.TEST_START
        if (testRequest && (capture != null || p?.let { it.enabled && now + 16 * 60_000 >= it.sensorStart && !it.finalDelivered && !it.earlyDelivered } == true)) {
            store.status("현재 수집 중이거나 기상 관측이 임박해 시험을 시작하지 않습니다.")
            if (capture == null) stopSelf(); return if (capture != null) START_STICKY else START_NOT_STICKY
        }
        val resumedTest = if (intent == null && activePlan == null) runCatching { DiagnosticArchive(this).use { a -> a.recent().firstOrNull {
            it.optBoolean("test") && it.optLong("stoppedAt") == 0L && now in it.optLong("plannedStart") until it.optLong("plannedEnd")
        } } }.getOrNull() else null
        val spec = when {
            activePlan != null -> CaptureSpec(activePlan.id, activePlan.sensorStart, activePlan.windowStart, activePlan.deadline, false)
            testRequest -> { val minutes = intent!!.getIntExtra("minutes", 10).coerceIn(10,15); CaptureSpec("test-${UUID.randomUUID()}", now, now + 5 * 60_000, now + minutes * 60_000, true) }
            resumedTest != null -> CaptureSpec(resumedTest.getString("id"), resumedTest.getLong("plannedStart"), resumedTest.getLong("windowStart"), resumedTest.getLong("plannedEnd"), true)
            capture?.spec?.test == true && now < capture!!.spec.end -> return START_STICKY
            else -> { endReason = "PLAN_INACTIVE_OR_WINDOW_END"; stopSelf(); return START_NOT_STICKY }
        }
        if (!MonitoringAccess.canMonitor(this)) { store.status("센서 권한 제한: 정시 알람 유지"); recordStartFailure(this, spec, "MONITORING_PERMISSION_DENIED"); stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("monitor-v1", "기상 관측·센서 시험", NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this, 701, NotificationCompat.Builder(this, "monitor-v1").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(if (spec.test) "화면 꺼짐 센서 수집 시험" else "기상 관측·센서 진단")
                .setContentText("정시 알람 독립 예약 · 기상 단계 분류 아님").setOngoing(true).build(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        } catch (e: Exception) { store.status("센서 FGS 제한: 정시 알람 유지 (${e.javaClass.simpleName})"); recordStartFailure(this, spec, e.javaClass.simpleName); stopSelf(); return START_NOT_STICKY }
        if (capture?.spec?.id == spec.id) return START_STICKY
        capture?.close("PREEMPTED_BY_ALARM_OR_NEW_SESSION")
        val reference = activePlan?.let { plan ->
            val saved = store.reports().find { it.id == plan.id }?.details?.profileSnapshot.orEmpty()
            if (saved.isNotBlank()) runCatching { reference(JSONObject(saved)) }.getOrNull() else null
        } ?: Personalization.reference(store.reports(), activePlan?.armedAt ?: now,
            store.sleepContext().takeIf { it.recordedAt <= (activePlan?.armedAt ?: now) } ?: SleepContext())
        val threshold = activePlan?.settings?.adaptiveThreshold ?: 0.72
        val profile = profile(reference, threshold)
        val comparison = CausalComparison(AdaptiveWakeEstimator(reference = reference, userThreshold = threshold))
        val next = MotionCapture(this, spec, { c, time ->
            c.state.put("profile", profile).put("algorithm", activePlan?.algorithm?.name ?: "TEST_NO_ALARM")
            if (!MonitoringAccess.canMonitor(this)) { finish(c, "MONITORING_PERMISSION_LOST", startId); return@MotionCapture }
            if (spec.test) { c.state.put("reason", "독립 센서 시험: 알람 판단·출력 없음"); return@MotionCapture }
            val current = store.plan()
            if (current?.id != spec.id || !current.enabled || current.finalDelivered || current.earlyDelivered || !MonitoringAccess.canMonitor(this)) {
                finish(c, "PLAN_CHANGED_OR_PERMISSION_LOST", startId); return@MotionCapture
            }
            if (time < spec.window) { store.status("개인 기준선 예열 중 · 진단에서 실제 관측률 확인 가능"); c.state.put("reason", "예열 중: 판단 구간 시작 전"); return@MotionCapture }
            val m = c.db.motion(spec.id, spec.start); val h = c.db.heart(spec.id, spec.start)
            val allowed = PassiveHeartMonitor.permitted(this)
            val elapsed = SystemClock.elapsedRealtime()
            val result = comparison.update(time, elapsed, spec.window, spec.end, m, if (allowed) h else emptyList())
            c.archive.tick(spec.id, JSONObject().put("now", time).put("elapsed", elapsed).put("generation", c.state.getInt("generation"))
                .put("motionIds", JSONArray(m.map { it.at })).put("heartIds", JSONArray(h.map { it.at }))
                .put("heartAllowed", allowed).put("legacyAt", result.legacyAt).put("adaptiveAt", result.adaptiveAt)
                .put("legacyReason", result.legacy.reason).put("adaptiveReason", result.adaptive.reason)
                .put("legacyWake", result.legacy.wake).put("adaptiveWake", result.adaptive.wake)
                .put("legacyConfidence", result.legacy.confidence).put("adaptiveConfidence", result.adaptive.confidence))
            val selected = if (activePlan!!.algorithm == AlgorithmId.LEGACY_011) result.legacy else result.adaptive
            c.state.put("reason", selected.reason)
            store.status(selected.reason)
            val e = result.adaptive.evidence
            store.updateReport(spec.id) { it.copy(motionBins = m.size, heartSamples = h.size, decision = selected.reason,
                details = it.details.copy(observationEnd = time, legacySuggestedAt = it.details.legacySuggestedAt.takeIf { at -> at > 0 } ?: result.legacyAt,
                    adaptiveSuggestedAt = it.details.adaptiveSuggestedAt.takeIf { at -> at > 0 } ?: result.adaptiveAt,
                    legacyReason = if (it.details.legacySuggestedAt > 0) it.details.legacyReason else result.legacy.reason,
                    adaptiveReason = if (it.details.adaptiveSuggestedAt > 0) it.details.adaptiveReason else result.adaptive.reason,
                    legacyConfidence = if (it.details.legacySuggestedAt > 0) it.details.legacyConfidence else result.legacy.confidence,
                    adaptiveConfidence = if (it.details.adaptiveSuggestedAt > 0) it.details.adaptiveConfidence else result.adaptive.confidence,
                    motionQuality = e?.motionQuality ?: 0.0, heartQuality = e?.heartQuality ?: 0.0,
                    referenceMedian = e?.motionMedian ?: 0.0, referenceMad = e?.motionMad ?: 0.0,
                    referenceHeart = e?.heartMedian ?: 0.0, referenceHeartMad = e?.heartMad ?: 0.0)) }
            val at = if (activePlan.algorithm == AlgorithmId.LEGACY_011) result.legacyAt else result.adaptiveAt
            if (at > 0) {
                c.persist(time) // Freeze what was observed before requesting the actual alarm.
                if (AlarmController(this).smartWake(spec.id)) finish(c, "SMART_ALARM_REQUESTED", startId)
            }
        }, { reason -> if (reason == "CLOCK_CHANGED" && !spec.test) { store.blockSmart(spec.id); store.status("시스템 시각 변경: 이번 조기 판단 중지, 정시 알람 유지") }; main.post { capture?.takeIf { it.spec.id == spec.id }?.let { finish(it, reason, startId) } } })
        CaptureRegistry.active = spec
        capture = next; endReason = "SERVICE_DESTROYED_OR_SYSTEM_STOP"
        if (!spec.test) runCatching { store.updateReport(spec.id) { it.copy(details = it.details.copy(
            batteryStart = if (it.details.batteryStartedAt == 0L) battery() else it.details.batteryStart,
            batteryStartedAt = it.details.batteryStartedAt.takeIf { t -> t > 0 } ?: now,
            personalizationOffset = reference.thresholdOffset, personalizedNights = reference.feedbackNights, profileSnapshot = profile.toString())) } }
        next.start()
        scope.launch {
            if (PassiveHeartMonitor.permitted(this@SmartWindowService)) {
                runCatching { heartMutex.withLock { withTimeout(15_000) { PassiveHeartMonitor.register(this@SmartWindowService) } } }
                    .onSuccess { next.heartState = "Passive Health Services 등록 완료" }
                    .onFailure { next.heartState = "심박 등록 실패/지연: ${it.javaClass.simpleName}; 움직임 경로 유지" }
            } else next.heartState = "심박 권한 없음: 움직임 기반 분석만 사용"
        }
        return START_STICKY
    }
    private fun finish(c: MotionCapture, reason: String, startId: Int) {
        main.post { if (capture === c) { endReason = reason; stopSelf() } }
    }
    override fun onDestroy() {
        val c = capture; capture = null
        if (CaptureRegistry.active?.id == c?.spec?.id) CaptureRegistry.active = null
        if (c != null && !c.spec.test && endReason == "SERVICE_DESTROYED_OR_SYSTEM_STOP") {
            val p = SettingsStore(this).plan()
            endReason = when {
                p?.id != c.spec.id -> "PLAN_REPLACED"
                p.finalDelivered -> "FINAL_ALARM_DELIVERED"
                p.earlyDelivered -> "SMART_ALARM_DELIVERED"
                !p.enabled -> "PLAN_CANCELLED"
                System.currentTimeMillis() >= c.spec.end -> "PLANNED_END"
                else -> endReason
            }
        }
        c?.close(endReason)
        if (c != null && !c.spec.test) runCatching { SettingsStore(this).updateReport(c.spec.id) { it.copy(details = it.details.copy(batteryEnd = battery())) } }
        scope.launch { runCatching { heartMutex.withLock { withTimeout(10_000) { PassiveHeartMonitor.unregister(this@SmartWindowService) } } }; scope.cancel() }
        runCatching { unregisterReceiver(stopTest) }
        sendBroadcast(Intent(AlarmController.ACTION_REPORT_UPDATED).setPackage(packageName))
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }; super.onDestroy()
    }
    companion object {
        private val heartMutex = Mutex()
        fun reference(j: JSONObject): PersonalReference {
            fun list(key: String): List<Double> = j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList()
            return PersonalReference(list("motionMedians"), list("motionMads"), j.optDouble("offset", 0.0), j.optInt("feedbackNights"), j.optJSONObject("sleepContext")?.let(JsonCodec::context) ?: SleepContext())
        }
        fun profile(r: PersonalReference, threshold: Double) = JSONObject().put("version", "adaptive-0.2.0").put("parameters", AdaptiveParameters().toString())
            .put("userThreshold", threshold).put("motionMedians", JSONArray(r.motionMedians)).put("motionMads", JSONArray(r.motionMads))
            .put("offset", r.thresholdOffset).put("feedbackNights", r.feedbackNights).put("sleepContext", JsonCodec.context(r.sleepContext))
        fun recordStartFailure(c: Context, spec: CaptureSpec, reason: String) { runCatching { DiagnosticArchive(c).use { a ->
            val j = a.get(spec.id) ?: JSONObject().put("id", spec.id).put("plannedStart", spec.start).put("windowStart", spec.window).put("plannedEnd", spec.end).put("test", spec.test)
            j.put("reason", "서비스 시작 실패: $reason"); a.save(j); a.event(spec.id, "FGS_START_FAILED $reason")
        } } }
    }
}
