package dev.sleepalarm.settings

import android.content.Context
import android.os.UserManager
import dev.sleepalarm.model.*
import org.json.JSONArray
import org.json.JSONObject

class SettingsStore(context: Context) {
    private val app = context.applicationContext
    // Minimal alarm state is available before first unlock after reboot.
    private val alarm = app.createDeviceProtectedStorageContext().getSharedPreferences("alarm-v1", Context.MODE_PRIVATE)
    private val unlocked: Boolean get() = app.getSystemService(UserManager::class.java).isUserUnlocked
    private val history get() = app.getSharedPreferences("private-history-v1", Context.MODE_PRIVATE)

    fun settings(): AlarmSettings = synchronized(LOCK) {
        runCatching { JsonCodec.settings(JSONObject(alarm.getString("settings", "")!!)) }.getOrDefault(AlarmSettings())
    }
    fun saveSettings(s: AlarmSettings) = synchronized(LOCK) {
        check(alarm.edit().putString("settings", JsonCodec.settings(s).toString()).commit())
    }
    fun plan(): AlarmPlan? = synchronized(LOCK) {
        runCatching { JsonCodec.plan(JSONObject(alarm.getString("plan", "")!!)) }.getOrNull()
    }
    fun savePlan(p: AlarmPlan) = synchronized(LOCK) {
        check(alarm.edit().putString("plan", JsonCodec.plan(p).toString()).commit())
    }
    // Separate from sensor status so a later sensor callback cannot hide an alarm failure.
    fun alarmError(): String = alarm.getString("alarmError", "")!!
    fun alarmError(message: String) { alarm.edit().putString("alarmError", message).commit() }
    fun reports(): List<NightReport> = synchronized(LOCK) {
        if (!unlocked) return@synchronized emptyList()
        runCatching {
            val a = JSONArray(history.getString("reports", "[]"))
            (0 until a.length()).map { JsonCodec.report(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
    fun updateReport(id: String, transform: (NightReport) -> NightReport) = synchronized(LOCK) {
        if (!unlocked) return@synchronized
        val list = reports().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = transform(list[index])
        history.edit().putString("reports", JSONArray(list.map { JsonCodec.report(it) }.take(60)).toString()).commit()
    }
    fun putReport(r: NightReport) = synchronized(LOCK) {
        if (!unlocked) return@synchronized
        val old = reports().find { it.id == r.id }
        var merged = r
        if (old != null && old.details.feedbackAt > r.details.feedbackAt) merged = merged.copy(details = merged.details.copy(
            refreshed = old.details.refreshed, sleepiness = old.details.sleepiness, feedbackAt = old.details.feedbackAt))
        if (old != null && old.details.healthComparedAt > r.details.healthComparedAt) merged = merged.copy(details = merged.details.copy(
            healthSleepMinutes = old.details.healthSleepMinutes, healthSleepEnd = old.details.healthSleepEnd, healthComparedAt = old.details.healthComparedAt))
        val list = (listOf(merged) + reports().filterNot { it.id == r.id }).sortedByDescending { it.armedAt }.take(60)
        history.edit().putString("reports", JSONArray(list.map(JsonCodec::report)).toString()).commit()
    }
    fun status(): String = if (unlocked) history.getString("sensorStatus", "센서 대기")!! else "잠금 해제 대기"
    fun status(message: String) { if (unlocked) history.edit().putString("sensorStatus", message).apply() }
    fun ack(): String = if (unlocked) history.getString("ack", "워치에서 예약 완료 응답을 확인하세요.")!! else "잠금 해제 필요"
    fun ack(message: String) { if (unlocked) history.edit().putString("ack", message).commit() }
    fun pendingRequest(id: String) { if (unlocked) history.edit().putString("request", id).commit() }
    fun pendingRequest(): String = if (unlocked) history.getString("request", "")!! else ""
    fun clearReports() { if (unlocked) history.edit().remove("reports").remove("sleepContext").commit() }
    fun sleepContext(): SleepContext = if (unlocked) runCatching {
        JsonCodec.context(JSONObject(history.getString("sleepContext", "{}")!!))
    }.getOrDefault(SleepContext()) else SleepContext()
    fun sleepContext(value: SleepContext) { if (unlocked) history.edit().putString("sleepContext", JsonCodec.context(value).toString()).commit() }
    fun feedback(id: String, refreshed: Int, sleepiness: Int, at: Long = System.currentTimeMillis()) {
        require(refreshed in 1..5 && sleepiness in 1..5 && at > 0 && at <= System.currentTimeMillis() + 30_000)
        updateReport(id) { r ->
            if (r.firstRingAt == 0L || at < r.firstRingAt || at < r.details.feedbackAt) r else r.copy(details = r.details.copy(
                refreshed = refreshed, sleepiness = sleepiness, feedbackAt = at))
        }
    }
    fun event(id: String, kind: String, action: String, now: Long = System.currentTimeMillis()) {
        updateReport(id) { it.copy(details = it.details.copy(events = (it.details.events + AlarmEvent(now, kind, action)).takeLast(100))) }
    }
    fun trialAlgorithm(mode: AlgorithmMode, day: String, randomBit: Boolean): AlgorithmId = synchronized(LOCK) {
        if (mode == AlgorithmMode.ADAPTIVE) return@synchronized AlgorithmId.ADAPTIVE_020
        if (mode == AlgorithmMode.LEGACY) return@synchronized AlgorithmId.LEGACY_011
        if (alarm.getString("trialDay", "") == day && alarm.getString("trialMode", "") == mode.name)
            return@synchronized AlgorithmId.valueOf(alarm.getString("trialAlgorithm", AlgorithmId.ADAPTIVE_020.name)!!)
        TrialAssignment.choose(mode, alarm.getInt("trialIndex", 0), randomBit)
    }
    fun commitTrial(mode: AlgorithmMode, day: String, algorithm: AlgorithmId) = synchronized(LOCK) {
        if (mode !in listOf(AlgorithmMode.ALTERNATE, AlgorithmMode.RANDOM)) return@synchronized
        val same = alarm.getString("trialDay", "") == day && alarm.getString("trialMode", "") == mode.name
        alarm.edit().putString("trialDay", day).putString("trialMode", mode.name).putString("trialAlgorithm", algorithm.name)
            .putInt("trialIndex", alarm.getInt("trialIndex", 0) + if (same) 0 else 1).commit()
    }
    fun blockedSession(): String = if (unlocked) history.getString("blockedSession", "")!! else ""
    fun blockSmart(id: String) { if (unlocked) history.edit().putString("blockedSession", id).commit() }

    companion object {
        /** All components use the default application process; no multiprocess SharedPreferences. */
        val LOCK = Any()
    }
}
