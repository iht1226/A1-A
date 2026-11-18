package dev.sleepalarm.sensors

import android.content.*
import android.hardware.display.DisplayManager
import android.os.*
import android.view.Display
import androidx.core.content.ContextCompat
import dev.sleepalarm.analysis.CoverageDiagnostics
import dev.sleepalarm.model.MotionSample
import org.json.JSONArray
import org.json.JSONObject

internal class CaptureEnvironment(private val context: Context, private val handler: Handler) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val changes = JSONArray()
    private val charging = JSONArray()
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) { if (id == Display.DEFAULT_DISPLAY) sampleDisplay() }
    }
    private val batteryListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = sampleBattery(intent)
    }
    private var displayRegistered = false
    private var batteryRegistered = false
    private var lastDisplay = ""
    private var lastCharging = ""
    private var percent = -1

    fun start(state: JSONObject, now: Long, previousHeartbeat: Long) {
        state.optJSONArray("displayChanges")?.let { copy(it, changes) }
        state.optJSONArray("chargingChanges")?.let { copy(it, charging) }
        if (previousHeartbeat > 0) {
            add(changes, previousHeartbeat, "UNKNOWN")
            add(charging, previousHeartbeat, "UNKNOWN")
        }
        sampleDisplay(now)
        displays.registerDisplayListener(displayListener, handler)
        displayRegistered = true
        ContextCompat.registerReceiver(context, batteryListener, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler,
            ContextCompat.RECEIVER_NOT_EXPORTED)?.let(::sampleBattery)
        batteryRegistered = true
        if (!state.has("captureBatteryStart")) state.put("captureBatteryStart", percent)
    }

    private fun copy(source: JSONArray, destination: JSONArray) {
        for (i in 0 until source.length()) destination.put(source.getJSONObject(i))
    }

    fun sampleDisplay(now: Long = System.currentTimeMillis()) {
        val observed = when (displays.getDisplay(Display.DEFAULT_DISPLAY)?.state) {
            Display.STATE_ON -> "ON"
            Display.STATE_OFF -> "OFF"
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "DOZE"
            else -> "UNKNOWN"
        }
        if (observed != lastDisplay) {
            add(changes, now, observed)
            lastDisplay = observed
        }
    }

    private fun sampleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
        percent = if (level in 0..scale && scale > 0) level * 100 / scale else -1
        val current = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
            else -> "UNKNOWN"
        }
        if (current != lastCharging) {
            add(charging, System.currentTimeMillis(), current)
            lastCharging = current
        }
    }

    private fun add(list: JSONArray, at: Long, value: String) {
        if (list.length() < 1023) list.put(JSONObject().put("at", at).put("state", value))
        else if (list.length() == 1023) list.put(JSONObject().put("at", at).put("state", "UNKNOWN"))
    }

    fun record(state: JSONObject, start: Long, end: Long, spans: List<Pair<Long, Long>>, motion: List<MotionSample>) {
        state.put("displayChanges", changes).put("chargingChanges", charging)
            .put("captureBatteryLast", percent)
            .put("displayCoverage", displayCoverage(changes, start, end, spans, motion))
    }

    fun close() {
        if (displayRegistered) runCatching { displays.unregisterDisplayListener(displayListener) }
        if (batteryRegistered) runCatching { context.unregisterReceiver(batteryListener) }
        displayRegistered = false
        batteryRegistered = false
    }

    companion object {
        /** Transition times are when the app observed a state, especially after suspension. */
        fun displayCoverage(changes: JSONArray, start: Long, end: Long, spans: List<Pair<Long, Long>>,
                            motion: List<MotionSample>): JSONObject {
            val boundaries = (0 until changes.length()).map { changes.getJSONObject(it) }
                .filter { it.getLong("at") < end }.sortedBy { it.getLong("at") }
            var cursor = start
            var current = "UNKNOWN"
            val totals = mutableMapOf<String, LongArray>()
            fun add(until: Long) {
                if (until <= cursor) return
                val coverage = CoverageDiagnostics.measure(cursor, until, spans)
                val row = totals.getOrPut(current) { LongArray(4) }
                row[0] += until - cursor
                row[1] += coverage.observedMillis
                row[2] += storedMillis(cursor, until, motion)
                row[3] = maxOf(row[3], coverage.maximumGapMillis)
                cursor = until
            }
            boundaries.forEach {
                val next = it.getString("state")
                if (next != current) {
                    add(it.getLong("at"))
                    current = next
                }
            }
            add(end)
            return JSONObject().apply {
                totals.forEach { (key, row) -> put(key, JSONObject().put("duration", row[0])
                    .put("observed", row[1]).put("stored", row[2]).put("gap", row[3])) }
            }
        }

        /** A partially overlapping bin can only guarantee the portion that cannot fall outside. */
        fun storedMillis(start: Long, end: Long, motion: List<MotionSample>): Long = motion.sumOf { bin ->
            val overlap = maxOf(0, minOf(end, bin.at + 5_000) - maxOf(start, bin.at))
            maxOf(0, bin.coveredMillis - (5_000 - overlap))
        }
    }
}
