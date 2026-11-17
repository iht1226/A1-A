package dev.sleepalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class AlarmController(private val context: Context) {
    private val store = SettingsStore(context)
    private val manager = context.getSystemService(AlarmManager::class.java)
    fun canSchedule(): Boolean = AlarmAccess.canSchedule(context)

    fun arm(settings: AlarmSettings, deadline: Long = settings.nextDeadline(Instant.now(), ZoneId.systemDefault()).toEpochMilli()): AlarmPlan =
        reservePlan(settings, deadline, saveUserSettings = true)

    /** One-shot test snapshot. It replaces the active reservation, never the user's preferences. */
    fun armTest(settings: AlarmSettings): AlarmPlan = reservePlan(
        settings.copy(smartEnabled = false), System.currentTimeMillis() + 120_000, saveUserSettings = false,
    )

    private fun reservePlan(settings: AlarmSettings, deadline: Long, saveUserSettings: Boolean): AlarmPlan =
        synchronized(SettingsStore.LOCK) {
            check(canSchedule()) { "정확한 알람 API가 예약 불가로 응답했습니다. 앱의 권한 진단에서 설치 권한·Android 버전을 확인하세요. 알림 권한과 별개입니다." }
            require(deadline > System.currentTimeMillis()) { "기상 시각이 이미 지났습니다." }
            val old = store.plan()
            val id = UUID.randomUUID().toString()
            val day = Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            val algorithm = if (saveUserSettings && settings.smartEnabled)
                store.trialAlgorithm(settings.algorithmMode, day, id.hashCode() and 1 == 0) else AlgorithmId.ADAPTIVE_020
            val next = AlarmPlan(id, System.currentTimeMillis(), deadline, settings, algorithm = algorithm)
            // Reserve the independent final alarm before any sensor-related work.
            reserve(next, FINAL, next.deadline)
            try { if (saveUserSettings) store.saveSettings(settings); store.savePlan(next) }
            catch (e: Exception) { cancelEvents(next); throw e }
            if (saveUserSettings && settings.smartEnabled) runCatching { store.commitTrial(settings.algorithmMode, day, algorithm) }
            runCatching { store.alarmError(""); AlarmNotifications.cancelFallback(context) }
            old?.let(::cancelEvents)
            context.stopService(Intent(context, RingingService::class.java))
            runCatching { store.putReport(NightReport(next.id, next.armedAt, next.deadline,
                details = NightDetails(algorithm = algorithm, mode = settings.algorithmMode,
                    smartScheduled = settings.smartEnabled, testAlarm = !saveUserSettings))) }
            scheduleWindow(next)
            changed()
            next
        }

    fun dismiss(nonce: String? = null) = synchronized(SettingsStore.LOCK) {
        val p = store.plan() ?: return@synchronized
        val next = AlarmTransitions.dismiss(p, nonce) ?: return@synchronized
        store.savePlan(next)
        if (next.enabled) listOf(WINDOW, SNOOZE, RECOVER).forEach { cancel(p, it) } else cancelEvents(p)
        context.stopService(Intent(context, RingingService::class.java))
        runCatching { AlarmNotifications.cancelFallback(context) }
        runCatching { AlarmNotifications.vibrator(context).cancel() }
        runCatching {
            val now = System.currentTimeMillis()
            store.updateReport(p.id) { it.copy(dismissedAt = now, details = it.details.copy(
                firstDismissedAt = it.details.firstDismissedAt.takeIf { at -> at > 0 } ?: now)) }
            store.event(p.id, p.ringKind?.name ?: "CANCEL", if (next.enabled) "DISMISS_KEEP_FINAL" else "DISMISS")
        }
        changed()
    }

    fun snooze(nonce: String) = synchronized(SettingsStore.LOCK) {
        val p = store.plan() ?: return@synchronized
        val next = AlarmTransitions.snooze(p, nonce, System.currentTimeMillis()) ?: return@synchronized
        check(canSchedule()) { "정확한 알람 권한이 필요합니다." }
        if (p.finalDelivered || next.snoozeAt < p.deadline) reserve(p, SNOOZE, next.snoozeAt)
        store.savePlan(next)
        // An early snooze does not cancel or move the original final deadline.
        context.stopService(Intent(context, RingingService::class.java))
        runCatching { AlarmNotifications.cancelFallback(context) }
        runCatching { AlarmNotifications.vibrator(context).cancel() }
        runCatching { store.event(p.id, p.ringKind?.name ?: "", "SNOOZE_TO_${next.snoozeAt}") }
        changed()
    }

    fun smartWake(id: String): Boolean = synchronized(SettingsStore.LOCK) {
        val p = store.plan() ?: return@synchronized false
        val now = System.currentTimeMillis()
        val next = AlarmTransitions.smart(p, id, now, UUID.randomUUID().toString()) ?: return@synchronized false
        ring(next, now)
        true
    }

    fun deliver(id: String, event: String, at: Long) = synchronized(SettingsStore.LOCK) {
        val p = store.plan() ?: return@synchronized
        if (!p.enabled || p.id != id) return@synchronized
        val now = System.currentTimeMillis()
        when (event) {
            FINAL -> AlarmTransitions.final(p, id, at, now, UUID.randomUUID().toString())?.let {
                cancel(p, SNOOZE); ring(it, now)
            }
            SNOOZE -> AlarmTransitions.snoozeDelivery(p, id, at, now, UUID.randomUUID().toString())?.let { ring(it, now) }
            WINDOW -> if (at == p.sensorStart && now < p.deadline && !p.earlyDelivered && !p.finalDelivered) {
                context.sendBroadcast(Intent(ACTION_WINDOW).setPackage(context.packageName))
            }
            RECOVER -> if (p.ringing) startRinging(p)
        }
    }

    private fun ring(p: AlarmPlan, now: Long) {
        store.savePlan(p)
        // Alarm execution must not depend on optional history storage being healthy.
        startRinging(p)
        runCatching { store.updateReport(p.id) {
            if (it.firstRingAt == 0L) it.copy(firstRingAt = now, firstRingKind = p.ringKind!!.name,
                details = it.details.copy(observationEnd = now)) else it
        } }
        runCatching { store.event(p.id, p.ringKind!!.name, "RING_REQUESTED", now) }
        changed()
    }

    private fun startRinging(p: AlarmPlan) {
        try {
            checkNotNull(context.startForegroundService(Intent(context, RingingService::class.java).putExtra("nonce", p.ringNonce))) {
                "알람 서비스를 찾거나 시작할 수 없습니다."
            }
        } catch (e: Exception) {
            // Surface a visible fallback instead of letting sensor/service exceptions escape the receiver.
            AlarmFailureReporter.report(context, p, "알람 서비스 시작", e)
        }
    }

    fun restore() = synchronized(SettingsStore.LOCK) {
        val p = store.plan() ?: return@synchronized
        if (!p.enabled) return@synchronized
        if (!canSchedule()) { store.status("정확한 알람 API 예약 불가: 앱 권한 진단과 설치 버전을 확인하세요. 일반 알림 권한과 별개입니다."); return@synchronized }
        val now = System.currentTimeMillis()
        when {
            p.ringing -> reserve(p, RECOVER, now + 2_000)
            p.snoozeAt > 0 -> {
                val requested = maxOf(now + 2_000, p.snoozeAt)
                val at = if (!p.finalDelivered) minOf(requested, p.deadline) else requested
                store.savePlan(p.copy(snoozeAt = at))
                if (p.finalDelivered || at < p.deadline) reserve(p, SNOOZE, at)
            }
        }
        if (!p.finalDelivered) {
            // Keep the original absolute deadline; an overdue alarm is delivered on recovery.
            reserve(p, FINAL, p.deadline)
            scheduleWindow(p)
        }
    }

    private fun scheduleWindow(p: AlarmPlan) {
        if (!p.settings.smartEnabled || p.earlyDelivered || p.finalDelivered) return
        try {
            if (System.currentTimeMillis() >= p.sensorStart && System.currentTimeMillis() < p.deadline)
                context.sendBroadcast(Intent(ACTION_WINDOW).setPackage(context.packageName))
            else if (p.sensorStart > System.currentTimeMillis())
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.sensorStart, pending(p, WINDOW, p.sensorStart))
        } catch (e: Exception) { store.status("스마트 분석 예약 제한: 일반 알람 유지") }
    }

    private fun reserve(p: AlarmPlan, event: String, at: Long) {
        val show = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        val showIntent = PendingIntent.getActivity(context, 90, show, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(maxOf(at, System.currentTimeMillis() + 500), showIntent), pending(p, event, at))
    }
    private fun pending(p: AlarmPlan, event: String, at: Long) = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java).apply {
            action = event
            data = Uri.parse("sleepalarm://alarm/${p.id}/$event")
            putExtra("id", p.id); putExtra("at", at)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    private fun cancel(p: AlarmPlan, event: String) { manager.cancel(pending(p, event, 0)) }
    private fun cancelEvents(p: AlarmPlan) { listOf(FINAL, WINDOW, SNOOZE, RECOVER).forEach { cancel(p, it) } }
    private fun changed() { context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName)) }

    companion object {
        const val FINAL = "final"
        const val WINDOW = "window"
        const val SNOOZE = "snooze"
        const val RECOVER = "recover"
        const val ACTION_WINDOW = "dev.sleepalarm.WINDOW"
        const val ACTION_CHANGED = "dev.sleepalarm.CHANGED"
        const val ACTION_REPORT_UPDATED = "dev.sleepalarm.REPORT_UPDATED"
    }
}
