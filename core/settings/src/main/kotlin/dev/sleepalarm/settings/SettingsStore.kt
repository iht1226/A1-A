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
        val list = (listOf(r) + reports().filterNot { it.id == r.id }).sortedByDescending { it.armedAt }.take(60)
        history.edit().putString("reports", JSONArray(list.map(JsonCodec::report)).toString()).commit()
    }
    fun status(): String = if (unlocked) history.getString("sensorStatus", "센서 대기")!! else "잠금 해제 대기"
    fun status(message: String) { if (unlocked) history.edit().putString("sensorStatus", message).apply() }
    fun ack(): String = if (unlocked) history.getString("ack", "워치에서 예약 완료 응답을 확인하세요.")!! else "잠금 해제 필요"
    fun ack(message: String) { if (unlocked) history.edit().putString("ack", message).commit() }
    fun pendingRequest(id: String) { if (unlocked) history.edit().putString("request", id).commit() }
    fun pendingRequest(): String = if (unlocked) history.getString("request", "")!! else ""
    fun clearReports() { if (unlocked) history.edit().remove("reports").commit() }
    fun blockedSession(): String = if (unlocked) history.getString("blockedSession", "")!! else ""
    fun blockSmart(id: String) { if (unlocked) history.edit().putString("blockedSession", id).commit() }

    companion object {
        /** All components use the default application process; no multiprocess SharedPreferences. */
        val LOCK = Any()
    }
}
