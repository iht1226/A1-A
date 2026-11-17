package dev.sleepalarm.wear

import android.app.NotificationManager
import android.content.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.settings.*
import dev.sleepalarm.sync.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == SyncProtocol.REFRESH) {
            runCatching { runBlocking { withTimeout(8_000) { WatchSync(this@WatchListenerService).publishReports() } } }
            return
        }
        if (event.path != SyncProtocol.ARM) return
        var request = ""
        val reply = runCatching {
            val j = SyncProtocol.parse(event.data)
            request = j.getString("request")
            require(request.length in 1..80)
            val inbox = getSharedPreferences("remote-inbox", MODE_PRIVATE)
            if (inbox.getString("last", "") == request) return@runCatching JSONObject(inbox.getString("reply", "{}")!!)
            val now = System.currentTimeMillis()
            require(j.getLong("createdAt") in (now - 120_000)..(now + 30_000) &&
                j.getLong("expiresAt") in now..(now + 150_000)) { "요청 만료 또는 기기 시각 불일치" }
            check(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { "워치 앱에서 알림 권한을 먼저 허용하세요." }
            val settings = JsonCodec.settings(j.getJSONObject("settings"))
            val p = AlarmController(this).arm(settings)
            JSONObject().put("version", 1).put("request", request).put("ok", true)
                .put("deadline", p.deadline).put("message", "워치에 독립 알람 예약 완료").also {
                    inbox.edit().putString("last", request).putString("reply", it.toString()).commit()
                }
        }.getOrElse {
            JSONObject().put("version", 1).put("request", request).put("ok", false).put("message", it.message ?: "워치 예약 실패")
        }
        runCatching { Tasks.await(Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, SyncProtocol.ACK, SyncProtocol.bytes(reply)), 5, TimeUnit.SECONDS) }
    }
}

class ReportSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { withTimeout(8_000) { WatchSync(context).publishReports() } }
            catch (_: Exception) { /* Durable local reports are retried by the explicit refresh flow. */ }
            finally { pending.finish() }
        }
    }
}
