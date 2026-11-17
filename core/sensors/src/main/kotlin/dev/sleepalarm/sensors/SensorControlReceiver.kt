package dev.sleepalarm.sensors

import android.content.*
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.settings.SettingsStore
import kotlinx.coroutines.*

class SensorControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = SettingsStore(context)
        val p = store.plan()
        val active = p != null && p.enabled && p.settings.smartEnabled && !p.earlyDelivered && !p.finalDelivered &&
            System.currentTimeMillis() < p.deadline && store.blockedSession() != p.id
        if (active && !PassiveHeartMonitor.permitted(context)) {
            store.status(SensorPermissionStatus.inspect(context).explanation)
            context.stopService(Intent(context, SmartWindowService::class.java))
            return
        }
        if (!active || (intent.action == AlarmController.ACTION_CHANGED && System.currentTimeMillis() < p!!.windowStart))
            context.stopService(Intent(context, SmartWindowService::class.java))
        if (active && (intent.action == AlarmController.ACTION_WINDOW || intent.action == Intent.ACTION_USER_UNLOCKED || intent.action == AlarmController.ACTION_CHANGED) &&
            System.currentTimeMillis() >= p!!.windowStart && PassiveHeartMonitor.permitted(context)) {
            runCatching { context.startForegroundService(Intent(context, SmartWindowService::class.java)) }
                .onFailure { store.status("스마트 분석 서비스 시작 제한: 일반 알람 유지") }
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    if (active) { PassiveHeartMonitor.register(context); store.status("저전력 심박 수신 대기 · 분석은 기상 범위에서 실행") }
                    else PassiveHeartMonitor.unregister(context)
                }
            } catch (e: Exception) { store.status("스마트 센서 제한: ${e.message?.take(100)} · 일반 알람 유지") }
            finally { pending.finish() }
        }
    }
}
