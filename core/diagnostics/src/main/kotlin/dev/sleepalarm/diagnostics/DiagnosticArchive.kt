package dev.sleepalarm.diagnostics

import android.content.*
import android.database.sqlite.*
import org.json.*
import dev.sleepalarm.analysis.CoverageDiagnostics
import java.time.*
import java.time.format.DateTimeFormatter

/** Credential-protected, local-only diagnostics. This class makes no network requests. */
class DiagnosticArchive(c: Context) : SQLiteOpenHelper(c, "sensor-diagnostics-v1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY, at INTEGER, json TEXT)")
        db.execSQL("CREATE TABLE events(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT, at INTEGER, reason TEXT)")
        db.execSQL("CREATE TABLE ticks(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT, json TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) = Unit
    fun get(id: String): JSONObject? = readableDatabase.rawQuery("SELECT json FROM runs WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) reconcile(JSONObject(it.getString(0))) else null
    }
    fun recent(): List<JSONObject> = readableDatabase.rawQuery("SELECT json FROM runs ORDER BY at DESC LIMIT 20", null).use {
        buildList { while (it.moveToNext()) add(reconcile(JSONObject(it.getString(0)))) }
    }
    fun latest(): JSONObject? = readableDatabase.rawQuery("SELECT json FROM runs ORDER BY at DESC, id DESC LIMIT 1", null).use {
        if (it.moveToFirst()) reconcile(JSONObject(it.getString(0))) else null
    }

    fun saveRemote(row: JSONObject) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val previous = get(row.getString("id"))
            if (previous == null || row.optLong("heartbeat") >= previous.optLong("heartbeat")) save(row)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Missing onDestroy is never reported as a known stop time; include the unobserved tail. */
    private fun reconcile(j: JSONObject): JSONObject {
        val end = j.optLong("plannedEnd")
        if (j.optBoolean("remoteSnapshot") || end <= 0 || System.currentTimeMillis() < end || j.optLong("censoredAt") > 0 ||
            (j.optBoolean("test") && j.optLong("stoppedAt") > 0)) return j
        val a = j.optJSONArray("spans") ?: JSONArray()
        val spans = (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
        val start = j.getLong("plannedStart"); val window = j.getLong("windowStart")
        fun phase(from: Long, to: Long): JSONObject {
            val c = CoverageDiagnostics.measure(from,to,spans)
            val previous = j.optJSONObject(if (from == start) "warmup" else "smart")
            val stored = previous?.optLong("storedCovered") ?: 0
            return JSONObject().put("storedCovered", stored).put("storedCoverage", if (to>from) stored.toDouble()/(to-from) else 0.0).put("coverage",c.ratio).put("observed",c.observedMillis).put("gap",c.maximumGapMillis).put("duration",to-from)
        }
        val c = CoverageDiagnostics.measure(start,end,spans)
        if (j.optLong("stoppedAt") == 0L) j.put("stopReason", "실제 종료 시각 미확인: 프로세스 종료/OS 제한/종료 콜백 미실행 가능. 마지막 저장 이후는 미관측.")
        val previousEnd = j.optLong("observedUntil", j.optLong("heartbeat", start)).coerceIn(start, end)
        val display = j.optJSONObject("displayCoverage")
        if (display != null && previousEnd < end) {
            val unknown = display.optJSONObject("UNKNOWN") ?: JSONObject()
            unknown.put("duration", unknown.optLong("duration") + end - previousEnd)
                .put("gap", maxOf(unknown.optLong("gap"), end - previousEnd))
            display.put("UNKNOWN", unknown)
        }
        if (j.has("storedCoverage") && end > start)
            j.put("storedCoverage", j.optDouble("storedCoverage") * (previousEnd - start) / (end - start))
        j.put("observedUntil",end)
            .put("coverage",c.ratio).put("maxGap",c.maximumGapMillis).put("warmup",phase(start,window)).put("smart",phase(window,end))
            .put("minutes",JSONArray(CoverageDiagnostics.minutes(start,end,spans).map {
                JSONObject().put("from",it.from).put("until",it.until).put("coverage",it.ratio).put("gap",it.maximumGapMillis).put("warmup",it.from<window)
            }))
        return j
    }
    fun save(j: JSONObject) {
        check(writableDatabase.insertWithOnConflict("runs", null, ContentValues().apply {
            put("id", j.getString("id")); put("at", j.getLong("plannedStart")); put("json", j.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE) >= 0)
        writableDatabase.execSQL("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY at DESC LIMIT 20)")
        listOf("events", "ticks").forEach { writableDatabase.execSQL("DELETE FROM $it WHERE id NOT IN (SELECT id FROM runs)") }
    }
    fun event(id: String, reason: String, at: Long = System.currentTimeMillis()) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply { put("id", id); put("at", at); put("reason", reason.take(500)) })
        writableDatabase.execSQL("DELETE FROM events WHERE id=? AND seq NOT IN (SELECT seq FROM events WHERE id=? ORDER BY seq DESC LIMIT 300)", arrayOf(id, id))
    }
    fun events(id: String): JSONArray = readableDatabase.rawQuery("SELECT at, reason FROM events WHERE id=? ORDER BY seq", arrayOf(id)).use {
        JSONArray().apply { while (it.moveToNext()) put(JSONObject().put("at", it.getLong(0)).put("reason", it.getString(1))) }
    }
    fun tick(id: String, j: JSONObject) {
        writableDatabase.insertOrThrow("ticks", null, ContentValues().apply { put("id", id); put("json", j.toString()) })
    }
    fun ticks(id: String): List<JSONObject> = readableDatabase.rawQuery("SELECT json FROM ticks WHERE id=? ORDER BY seq", arrayOf(id)).use {
        buildList { while (it.moveToNext()) add(reconcile(JSONObject(it.getString(0)))) }
    }
    fun erase() { listOf("ticks","events","runs").forEach { writableDatabase.delete(it,null,null) } }
    fun shareable(j: JSONObject): JSONObject = JSONObject(j.toString()).apply {
        remove("spans"); remove("profile"); put("remoteSnapshot", true); if (!has("events")) put("events", events(j.getString("id")))
    }
    companion object {
        const val CPU_HOLD = "cpuHold"
        const val TEST_START = "dev.sleepalarm.SENSOR_TEST_START"
        const val TEST_STOP = "dev.sleepalarm.SENSOR_TEST_STOP"
        const val REPLAY = "dev.sleepalarm.SENSOR_REPLAY"
        fun clock(at: Long): String = if (at <= 0) "미확인" else Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss"))
        fun summary(j: JSONObject?): String {
            if (j == null) return "상세 센서 진단 없음 (이전 버전 기록 또는 아직 수집 전)"
            fun phase(key: String): String {
                val p = j.optJSONObject(key) ?: return "$key: 아직 관측 전"
                return "${if (key == "warmup") "예열" else "판단"}: 이벤트 ${"%.1f".format(p.optDouble("coverage") * 100)}% · 저장 ${if (p.has("storedCoverage")) "%.1f%%".format(p.optDouble("storedCoverage")*100) else "미확인"} · ${p.optLong("observed") / 1000}/${p.optLong("duration") / 1000}초 · 최대 공백 ${p.optLong("gap") / 1000}초"
            }
            return phase("warmup") + "\n" + phase("smart") + "\n보류/판단: " + j.optString("reason", "평가 전")
        }
        fun text(j: JSONObject): String = buildString {
            append("센서 진단 ${j.optString("version")} · ${if (j.optBoolean("test")) "독립 수집 시험" else "기상 관측"}\nID ${j.optString("id")}\n")
            append("예정 ${clock(j.optLong("plannedStart"))} ~ ${clock(j.optLong("plannedEnd"))}\n")
            append("서비스 명령 ${clock(j.optLong("serviceCommandAt"))} · 센서 등록 ${clock(j.optLong("registeredAt"))}\n")
            append("실제 시작 ${clock(j.optLong("actualStart"))} · 최근 시작 ${clock(j.optLong("segmentStart"))}\n")
            append("관측 비교 종료 ${clock(j.optLong("censoredAt").takeIf { it > 0 } ?: j.optLong("observedUntil"))} · 실제 알람 이후를 가정하지 않습니다.\n")
            append("실제 종료 ${clock(j.optLong("stoppedAt"))} · 마지막 저장 ${clock(j.optLong("heartbeat"))}\n")
            append("${j.optString("stopReason", "수집 중/종료 미확인")} · 시작 지연 ${j.optLong("startDelay") / 1000}초\n")
            append("센서 ${j.optString("sensor", "선택/등록 전")}\nwake-up ${j.optBoolean("wakeUp")} · FIFO max/reserved ${j.optInt("fifoMax")}/${j.optInt("fifoReserved")}\n")
            append("요청 ${j.optInt("samplingUs")}us · 배치 ${j.optInt("latencyUs")}us\n${j.optString("limitation")}\n")
            append("이벤트 ${j.optLong("received")} · 채택 ${j.optLong("accepted")} · 유효 저장 구간 ${j.optInt("bins")}\n")
            append("실측 ${clock(j.optLong("lastMeasured"))} · 수신 ${clock(j.optLong("lastReceived"))} · 최대 지연 ${j.optLong("maxDelay") / 1000}초\n")
            append("최대 수신 공백 ${j.optLong("maxReceiveGap") / 1000}초 · 60초 초과 지연 채택 ${j.optLong("delayedOver60s")}회\n")
            append("폐기 사유 ${j.optJSONObject("discard") ?: "{}"}\n정확도 상태 ${j.optJSONObject("accuracy") ?: "{}"}\n")
            append("서비스 재시작 ${maxOf(0,j.optInt("generation") - 1)} · 센서 재등록 ${j.optInt("reRegistrations")} · 중복 구간 ${j.optInt("duplicateBins")} · DB 오류 ${j.optInt("dbErrors")}\n")
            append("DB 쓰기 ${j.optLong("dbWriteOps")}회 · 최대 ${j.optLong("dbWriteMaxMs")}ms · 합계 ${j.optLong("dbWriteTotalMs")}ms · 분석 최대 ${j.optLong("analysisMaxMs")}ms\n")
            append("FIFO flush ${j.optInt("flushRequests")}/${j.optInt("flushCompleted")} · 마지막 오류 ${j.optString("lastError", "없음")}\n")
            append("심박 ${j.optInt("heartCount")} · 품질≥0.7 ${j.optInt("heartGood")} · 최근 ${clock(j.optLong("heartLast"))} · ${j.optString("heartState", "등록 미확인")}\n")
            if (j.optBoolean("spanDetailTruncated")) append("관측 범위 상세 상한 도달: 관측률은 보수적 하한이며 전체 정확도는 미확정입니다.\n")
            append(summary(j)); append("\n전체 이벤트 관측률 ${if (j.has("coverage")) "%.1f%%".format(j.optDouble("coverage") * 100) else "미계산"} · 최대 공백 ${j.optLong("maxGap") / 1000}초\n")
            append("저장 구간 관측시간 ${j.optLong("storedCovered") / 1000}초 · 부분 구간/공백을 정지로 채우지 않습니다.\n")
            if (j.has("cpuRequested")) {
                append("수집 방식: ${if (j.optBoolean("cpuRequested")) "CPU 유지 (실험적)" else "기본 절전"}\n")
                j.optJSONObject("wakeLock")?.let { lock ->
                    append("WakeLock ${lock.optString("status")} · 제한 ${lock.optLong("timeoutMs") / 1000}초\n")
                    append("획득 ${clock(lock.optLong("acquiredAt"))} · 해제 확인 ${clock(lock.optLong("releasedAt"))}\n")
                    if (lock.optString("status") == "TIMEOUT_OR_OS_RELEASE_OBSERVED")
                        append("timeout 또는 OS 해제는 감지 시각을 표시합니다. 실제 해제 시각은 더 이를 수 있습니다.\n")
                    append("확인된 유지 시간 ${j.optLong("wakeHeldTotalMs") / 1000}초 (이전 수집 구간 포함)\n")
                    if (j.optBoolean("wakePreviousStopUnknown") || (j.optLong("stoppedAt") == 0L && lock.optString("status") == "HELD"))
                        append("마지막 기록 이후 실제 해제 시각 및 유지 시간은 확인이 필요합니다.\n")
                }
                fun percent(key: String) = j.optInt(key, -1).takeIf { it >= 0 }?.let { "$it%" } ?: "미확인"
                append("배터리 ${percent("captureBatteryStart")} → ${percent("captureBatteryLast")} (잔량 참고값)\n")
                append("전체 저장 관측률 ${"%.1f%%".format(j.optDouble("storedCoverage", 0.0) * 100)}\n")
                j.optJSONObject("displayCoverage")?.let { coverage ->
                    val labels = mapOf("ON" to "켜짐", "OFF" to "꺼짐", "DOZE" to "앰비언트", "UNKNOWN" to "미확인")
                    labels.forEach { (key, label) -> coverage.optJSONObject(key)?.let { row ->
                        val span = row.optLong("duration").coerceAtLeast(1)
                        val observed = row.optLong("observed")
                        append("화면 $label ${span / 1000}초 · 실측 ${"%.1f%%".format(100.0 * observed / span)} · 저장 ${"%.1f%%".format(100.0 * row.optLong("stored") / span)} · 실측 누락 ${(span - observed) / 1000}초 · 최대 공백 ${row.optLong("gap") / 1000}초\n")
                    } }
                }
                listOf("displayChanges" to "화면 상태 변경 감지", "chargingChanges" to "충전 상태").forEach { (key, label) ->
                    val items = j.optJSONArray(key) ?: JSONArray()
                    append("$label:\n")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        append("${clock(item.optLong("at"))} ${item.optString("state")}\n")
                    }
                }
            }
            append("시간대별(실측 시각 기준):\n")
            val minutes = j.optJSONArray("minutes") ?: JSONArray()
            for (i in 0 until minutes.length()) {
                val m = minutes.getJSONObject(i)
                append("${clock(m.optLong("from"))} ${if (m.optBoolean("warmup")) "예열" else "판단"} ${"%.1f".format(m.optDouble("coverage") * 100)}% · 공백 ${m.optLong("gap") / 1000}초\n")
            }
            append("리플레이: ${j.optString("replay", "워치의 원본과 평가 시각이 필요합니다. 실제 하드웨어 시험과 다릅니다.")}\n")
            val events = j.optJSONArray("events") ?: JSONArray()
            append("서비스/센서 사건:\n")
            for (i in 0 until events.length()) { val e = events.getJSONObject(i); append("${clock(e.optLong("at"))} ${e.optString("reason")}\n") }
            append("진단을 외부 서버로 전송하지 않습니다. 휴대전화 전송은 사용자가 요청한 근거리 연결만 사용합니다.\n")
        }
    }
}
