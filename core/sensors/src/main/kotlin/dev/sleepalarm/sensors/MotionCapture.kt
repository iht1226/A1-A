package dev.sleepalarm.sensors

import android.content.Context
import android.hardware.*
import android.os.*
import dev.sleepalarm.analysis.*
import dev.sleepalarm.diagnostics.DiagnosticArchive
import dev.sleepalarm.model.MotionSample
import org.json.*
import kotlin.math.*

data class CaptureSpec(val id: String, val start: Long, val window: Long, val end: Long, val test: Boolean, val cpuHold: Boolean = false)

/** One serial sensor/DB/analysis thread per run. The optional CPU lease belongs to this capture segment. */
class MotionCapture(private val context: Context, val spec: CaptureSpec,
    private val onTick: (MotionCapture, Long) -> Unit, private val onEnd: (String) -> Unit) : SensorEventListener2 {
    private val thread = HandlerThread("sleep-capture").apply { start() }
    val handler = Handler(thread.looper)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    lateinit var db: SensorDatabase; private set
    lateinit var archive: DiagnosticArchive; private set
    lateinit var state: JSONObject; private set
    private val cpuLease = CaptureWakeLock.forContext(context)
    private val environment = CaptureEnvironment(context, handler)
    private var priorWakeMs = 0L
    private val accumulator = MotionAccumulator()
    private val audit = mutableListOf<SensorDatabase.Audit>()
    private val spans = mutableListOf<Pair<Long, Long>>()
    private var sensor: Sensor? = null
    private var anchor = 0L
    private var lastNanos = 0L
    private var lastReceived = 0L
    private var lastErrorReport = 0L
    private var lastRegister = 0L
    private var recoveryAt = 0L
    private var flushPendingAt = 0L
    @Volatile private var closing = false
    @Volatile var heartState: String = "심박 등록 전/선택 사항"
    private val commandAt = System.currentTimeMillis()
    private var closed = false
    private var observationLimit = spec.end
    private var screen: Boolean? = null
    private var idle: Boolean? = null
    private val loop = object : Runnable {
        override fun run() {
            if (closing) return
            val now = System.currentTimeMillis()
            try {
                if (now >= spec.end) { end("PLANNED_END"); return }
                if (abs(now - SystemClock.elapsedRealtime() - anchor) > 2_000) { end("CLOCK_CHANGED"); return }
                health(now); flushAudit()
                val analysisStart = SystemClock.elapsedRealtime()
                onTick(this@MotionCapture, now)
                state.put("analysisMaxMs", maxOf(state.optLong("analysisMaxMs"), SystemClock.elapsedRealtime()-analysisStart))
                persist(now)
            } catch (e: Exception) {
                error("TICK_${e.javaClass.simpleName}")
                if (spec.cpuHold) { end("CPU_CAPTURE_ERROR"); return }
            }
            if (!closing) handler.postDelayed(this, 30_000)
        }
    }
    fun start() = handler.post {
        if (closing) return@post
        try {
            db = SensorDatabase(context); db.prune(System.currentTimeMillis()); archive = DiagnosticArchive(context)
            val now = System.currentTimeMillis()
            state = archive.get(spec.id) ?: JSONObject().put("id", spec.id).put("plannedStart", spec.start)
                .put("windowStart", spec.window).put("plannedEnd", spec.end).put("test", spec.test).put("actualStart", now)
                .put("startDelay", maxOf(0, now - spec.start)).put("received", 0).put("accepted", 0)
                .put("discard", JSONObject()).put("accuracy", JSONObject()).put("generation", 0)
            state.optJSONArray("spans")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { spans += it.getLong(0) to it.getLong(1) } }
            if (!state.has("discard")) state.put("discard", JSONObject())
            if (!state.has("accuracy")) state.put("accuracy", JSONObject())
            state.put("serviceCommandAt", commandAt)
            state.put("actualStart", state.optLong("actualStart").takeIf { it > 0 } ?: now)
                .put("startDelay", maxOf(0, (state.optLong("actualStart").takeIf { it > 0 } ?: now) - spec.start))
            val previousHeartbeat = state.optLong("heartbeat")
            priorWakeMs = state.optLong("wakeHeldTotalMs")
            if (state.optJSONObject("wakeLock")?.optString("status") == "HELD")
                state.put("wakePreviousStopUnknown", true)
            state.put("cpuRequested", spec.cpuHold)
            state.put("version", "capture-0.5.0/adaptive-0.2.0").put("generation", state.optInt("generation") + 1)
                .put("segmentStart", now).put("stoppedAt", 0).put("stopReason", "").put("heartbeat", now)
            anchor = now - SystemClock.elapsedRealtime()
            archive.save(state)
            archive.event(spec.id, if (state.optInt("generation") > 1) "SERVICE_RESTART: previous process/segment ended or was interrupted; no gap filled" else "SERVICE_START")
            if (!register(now)) { end("REGISTER_FAILED"); return@post }
            if (closing) return@post
            runCatching { environment.start(state, now, previousHeartbeat) }
                .onFailure { error("ENVIRONMENT_${it.javaClass.simpleName}") }
            if (spec.cpuHold) {
                cpuLease.acquire(spec.end - System.currentTimeMillis())
                val snapshot = cpuLease.snapshot()
                archive.event(spec.id, "WAKE_LOCK ${snapshot.getString("status")} timeoutMs=${snapshot.getLong("timeoutMs")}")
            }
            persist(System.currentTimeMillis())
            handler.post(loop)
        } catch (e: Exception) { runCatching { error("START_${e.javaClass.simpleName}") }; end("CAPTURE_START_FAILED") }
    }
    private fun register(now: Long): Boolean {
        val candidates = sensors.getSensorList(Sensor.TYPE_ACCELEROMETER).filter { it.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS }
            .sortedWith(compareByDescending<Sensor> { it.isWakeUpSensor }.thenByDescending { it.fifoReservedEventCount }.thenByDescending { it.fifoMaxEventCount })
        state.put("availableSensors", JSONArray(candidates.map { describe(it) }))
        for (candidate in candidates) {
            val latency = CapturePolicy.latencyUs(candidate.fifoMaxEventCount, candidate.fifoReservedEventCount)
            val success = runCatching { sensors.registerListener(this, candidate, CapturePolicy.SAMPLING_US, latency, handler) }
            archive.event(spec.id, "REGISTER ${candidate.name} wake=${candidate.isWakeUpSensor} fifo=${candidate.fifoMaxEventCount}/${candidate.fifoReservedEventCount} latencyUs=$latency result=${success.getOrDefault(false)} ${success.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}")
            if (success.getOrDefault(false)) {
                sensor = candidate; lastRegister = now; flushPendingAt = 0
                state.put("registeredAt", System.currentTimeMillis())
                state.put("sensor", describe(candidate)).put("wakeUp", candidate.isWakeUpSensor)
                    .put("fifoMax", candidate.fifoMaxEventCount).put("fifoReserved", candidate.fifoReservedEventCount)
                    .put("samplingUs", CapturePolicy.SAMPLING_US).put("latencyUs", latency)
                    .put("limitation", if (!candidate.isWakeUpSensor) "NON_WAKE_UP: AP suspend 동안 전달/연속 관측 보장 불가. FIFO 덮어쓰기 가능. FGS는 CPU를 계속 깨우지 않습니다."
                        else if (latency == 0) "wake-up이지만 FIFO 없음: 저주파 요청이어도 이벤트별 CPU 기상이 발생할 수 있습니다. 배터리 실측 필요."
                        else "wake-up FIFO 배치 사용. 공유 FIFO·OEM 절전·실제 주기·배터리는 실측이 필요합니다.")
                return true
            }
        }
        return false
    }
    private fun describe(s: Sensor) = "${s.name} / ${s.vendor} v${s.version} type=${s.type} wake=${s.isWakeUpSensor} power=${s.power}mA minDelay=${s.minDelay}us maxDelay=${s.maxDelay}us"
    override fun onSensorChanged(event: SensorEvent) {
        if (closed || !::state.isInitialized) return
        val received = System.currentTimeMillis()
        val measured = anchor + event.timestamp / 1_000_000
        increment(state, "received")
        state.put("maxReceiveGap", maxOf(state.optLong("maxReceiveGap"), maxOf(0, received - maxOf(lastReceived,lastRegister))))
        state.put("lastReceived", received); lastReceived = received
        val accuracy = state.getJSONObject("accuracy"); increment(accuracy, event.accuracy.toString())
        val energy = if (event.values.size >= 3) abs(sqrt(event.values.take(3).sumOf { it.toDouble().pow(2) }) - SensorManager.GRAVITY_EARTH) / SensorManager.GRAVITY_EARTH else null
        val reason = if (closing) "STOPPING" else CapturePolicy.reject(measured, received, spec.start, spec.end, event.timestamp, lastNanos, energy, event.accuracy)
        if (reason != null) increment(state.getJSONObject("discard"), reason)
        else {
            increment(state, "accepted"); state.put("lastMeasured", measured)
            if (received - measured > 60_000) increment(state, "delayedOver60s")
            state.put("maxDelay", maxOf(state.optLong("maxDelay"), maxOf(0, received - measured)))
            accumulator.add(measured, energy!!, received)?.let(::saveMotion)
            accumulator.lastInterval?.let { span ->
                if (spans.lastOrNull()?.second == span.first) spans[spans.lastIndex] = spans.last().first to span.second
                else if (spans.size < 4096) spans += span
                else state.put("spanDetailTruncated", true)
            }
        }
        if (event.timestamp > lastNanos && reason !in setOf("INVALID_TIMESTAMP", "FUTURE_TIMESTAMP", "AFTER_CAPTURE")) lastNanos = event.timestamp
        audit += SensorDatabase.Audit(event.timestamp, measured, received, energy, event.accuracy, reason ?: "ACCEPTED")
        if (audit.size >= 256) flushAudit()
    }
    private fun saveMotion(p: MotionSample) {
        val began = SystemClock.elapsedRealtime()
        try { if (!db.addMotion(spec.id, p)) increment(state, "duplicateBins") }
        catch (e: Exception) { error("MOTION_DB_${e.javaClass.simpleName}") }
        finally { recordWriteTime(began) }
    }
    private fun flushAudit() {
        if (audit.isEmpty()) return
        val began = SystemClock.elapsedRealtime()
        try { db.audit(spec.id, audit); audit.clear() }
        catch (e: Exception) { error("AUDIT_DB_${e.javaClass.simpleName}"); if (audit.size > 1024) { state.put("auditLost", state.optInt("auditLost") + audit.size); audit.clear() } }
        finally { recordWriteTime(began) }
    }
    private fun recordWriteTime(began: Long) {
        val dt = maxOf(0, SystemClock.elapsedRealtime()-began)
        state.put("dbWriteMaxMs", maxOf(state.optLong("dbWriteMaxMs"),dt))
        state.put("dbWriteTotalMs", state.optLong("dbWriteTotalMs")+dt); increment(state,"dbWriteOps")
    }
    internal fun health(now: Long) {
        environment.sampleDisplay(now)
        val interactive = power.isInteractive; val doze = power.isDeviceIdleMode
        if (screen != interactive || idle != doze) { archive.event(spec.id, "POWER screen=$interactive idle=$doze save=${power.isPowerSaveMode}"); screen = interactive; idle = doze }
        val silence = now - maxOf(lastReceived, lastRegister)
        if (silence >= 30_000 && flushPendingAt == 0L) {
            flushPendingAt = now
            increment(state, "flushRequests")
            archive.event(spec.id, "FIFO_FLUSH_REQUEST silenceMs=$silence result=${runCatching { sensors.flush(this) }.getOrDefault(false)}")
        }
        val retries = state.optInt("reRegistrations")
        if (silence >= 60_000 && retries < 3 && now >= recoveryAt) {
            archive.event(spec.id, "SENSOR_RE_REGISTER silenceMs=$silence (handler ran; this does not wake a suspended AP)")
            sensors.unregisterListener(this); state.put("reRegistrations", retries + 1)
            recoveryAt = now + 60_000L * (1L shl retries)
            if (!register(now)) archive.event(spec.id, "RECOVERY_REGISTER_FAILED")
        }
    }
    override fun onFlushCompleted(sensor: Sensor?) { flushPendingAt = 0; if (::state.isInitialized) increment(state, "flushCompleted"); runCatching { archive.event(spec.id, "FIFO_FLUSH_COMPLETED") } }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { if (::archive.isInitialized) runCatching { archive.event(spec.id, "ACCURACY_CHANGED $accuracy") } }
    fun error(reason: String) {
        if (::state.isInitialized) { increment(state, if (reason.contains("DB")) "dbErrors" else "serviceErrors"); state.put("lastError", reason) }
        val now = System.currentTimeMillis()
        if (now - lastErrorReport >= 10_000) {
            lastErrorReport = now
            android.util.Log.e("SleepAlarm", "Capture ${spec.id}: $reason; final alarm independent")
            runCatching { dev.sleepalarm.settings.SettingsStore(context).status("센서/진단 오류: $reason · 정시 알람 유지") }
            if (::archive.isInitialized) runCatching { archive.event(spec.id, reason) }
        }
    }

    fun persist(now: Long) {
        val until = minOf(now, spec.end, observationLimit)
        val motion = db.motion(spec.id, spec.start)
        val heart = db.heart(spec.id, spec.start)
        fun phase(from: Long, to: Long): JSONObject {
            val c = CoverageDiagnostics.measure(from, maxOf(from, to), spans)
            val duration = maxOf(0, c.until - c.from)
            val stored = motion.sumOf { p ->
                val overlap = maxOf(0, minOf(to, p.at + 5_000) - maxOf(from,p.at))
                maxOf(0, p.coveredMillis - (5_000 - overlap))
            }
            return JSONObject().put("storedCovered", stored).put("storedCoverage", if (duration > 0) stored.toDouble()/duration else 0.0).put("coverage", c.ratio).put("observed", c.observedMillis).put("gap", c.maximumGapMillis).put("duration", maxOf(0, c.until - c.from))
        }
        val total = CoverageDiagnostics.measure(spec.start, maxOf(spec.start, until), spans)
        state.put("observedUntil", until).put("heartbeat", now).put("bins", motion.size).put("storedCovered", motion.sumOf { it.coveredMillis })
            .put("heartCount", heart.size).put("heartGood", heart.count { it.quality >= 0.7 }).put("heartLast", heart.lastOrNull()?.at ?: 0)
            .put("heartState", if (PassiveHeartMonitor.permitted(context)) heartState else "심박 권한 없음/회수: 캐시 심박을 판단에 사용하지 않음").put("coverage", total.ratio).put("maxGap", total.maximumGapMillis)
            .put("warmup", phase(spec.start, minOf(spec.window, until))).put("smart", phase(spec.window, until))
            .put("spans", JSONArray(spans.map { JSONArray(listOf(it.first,it.second)) }))
            .put("minutes", JSONArray(CoverageDiagnostics.minutes(spec.start, maxOf(spec.start, until), spans).map {
                JSONObject().put("from", it.from).put("until", it.until).put("coverage", it.ratio).put("gap", it.maximumGapMillis).put("warmup", it.from < spec.window)
            }))
        val lock = cpuLease.snapshot()
        state.put("wakeLock", lock).put("wakeHeldTotalMs", priorWakeMs + lock.getLong("heldMs"))
        environment.record(state, spec.start, until, spans, motion)
        state.put("storedCoverage", if (until > spec.start)
            CaptureEnvironment.storedMillis(spec.start, until, motion).toDouble() / (until - spec.start) else 0.0)
        archive.save(state)
    }

    private fun end(reason: String) {
        cpuLease.release()
        onEnd(reason)
    }

    fun close(reason: String) {
        if (closing) return
        closing = true
        cpuLease.release()
        runCatching { sensors.unregisterListener(this) }; handler.removeCallbacks(loop)
        handler.post {
            try {
                runCatching { sensors.unregisterListener(this) }
                environment.close()
                if (::state.isInitialized && ::db.isInitialized && ::archive.isInitialized) {
                    val now = System.currentTimeMillis()
                    if (!spec.test) {
                        val first = dev.sleepalarm.settings.SettingsStore(context).reports().find { it.id == spec.id }?.firstRingAt ?: 0
                        if (first > 0) { observationLimit = minOf(observationLimit, first); state.put("censoredAt", first) }
                    }
                    accumulator.finish(now)?.let(::saveMotion); flushAudit(); state.put("stoppedAt", now).put("stopReason", reason)
                    if (spec.cpuHold) {
                        val lock = cpuLease.snapshot()
                        archive.event(spec.id, "WAKE_LOCK ${lock.getString("status")} heldMs=${lock.getLong("heldMs")} acquiredAt=${lock.getLong("acquiredAt")} releasedAt=${lock.getLong("releasedAt")}")
                    }
                    archive.event(spec.id, "SERVICE_STOP $reason"); persist(now)
                    if (!spec.test) runCatching { dev.sleepalarm.settings.SettingsStore(context).updateReport(spec.id) {
                        it.copy(motionBins = state.optInt("bins"), heartSamples = state.optInt("heartCount"))
                    }; context.sendBroadcast(android.content.Intent(dev.sleepalarm.alarm.AlarmController.ACTION_REPORT_UPDATED).setPackage(context.packageName)) }
                }
            } catch (e: Exception) { android.util.Log.e("SleepAlarm", "Diagnostic/capture shutdown failed; final alarm remains independent", e) }
            finally { closed = true; runCatching { if (::db.isInitialized) db.close() }; runCatching { if (::archive.isInitialized) archive.close() }; thread.quitSafely() }
        }
    }
    private fun increment(j: JSONObject, key: String) { j.put(key, j.optLong(key) + 1) }
}
