package dev.sleepalarm.sensors

import android.content.*
import androidx.health.services.client.HealthServices
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.settings.SettingsStore

class SensorControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = SettingsStore(context)
        val p = store.plan()
        val active = p != null && p.enabled && p.settings.smartEnabled && !p.earlyDelivered && !p.finalDelivered &&
            System.currentTimeMillis() < p.deadline && store.blockedSession() != p.id
        if (!active || System.currentTimeMillis() < p!!.sensorStart) {
            context.stopService(Intent(context, SmartWindowService::class.java))
            if (!active) runCatching { HealthServices.getClient(context).passiveMonitoringClient.clearPassiveListenerServiceAsync() }
            return
        }
        if (!MonitoringAccess.canMonitor(context)) {
            store.status("백그라운드 움직임 분석 권한 또는 항상 허용 심박 권한이 필요합니다. 일반 알람 유지")
            return
        }
        runCatching { context.startForegroundService(Intent(context, SmartWindowService::class.java)) }
            .onFailure { store.status("스마트 분석 서비스 시작 제한: 일반 알람 유지 (${it.javaClass.simpleName})") }
    }
}
