package dev.sleepalarm.sync

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.*
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

object SyncProtocol {
    const val ARM = "/sleep/v1/arm"
    const val ACK = "/sleep/v1/ack"
    const val REFRESH = "/sleep/v1/refresh"
    const val REPORTS = "/sleep/v1/reports/"
    const val CONTEXT = "/sleep/v2/context"
    const val FEEDBACK = "/sleep/v2/feedback"
    fun bytes(json: JSONObject): ByteArray = json.toString().toByteArray(Charsets.UTF_8)
    fun parse(data: ByteArray): JSONObject {
        require(data.size <= 64_000)
        return JSONObject(data.toString(Charsets.UTF_8)).also { require(it.getInt("version") == 1) }
    }
}

class WatchSync(private val context: Context) {
    /** Ephemeral commands deliberately do not use persistent DataItems: no late alarm replays. */
    suspend fun armWatch(settings: AlarmSettings): String {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        check(nodes.size == 1) { "연결된 워치가 정확히 1대여야 합니다. 워치에서 직접 예약할 수도 있습니다." }
        val request = UUID.randomUUID().toString()
        val store = SettingsStore(context)
        store.pendingRequest(request)
        store.ack("워치 확인 응답 대기 중입니다. 아직 예약 완료로 간주하지 마세요.")
        val now = System.currentTimeMillis()
        val json = JSONObject().put("version", 1).put("request", request)
            .put("createdAt", now).put("expiresAt", now + 120_000).put("settings", JsonCodec.settings(settings))
        Wearable.getMessageClient(context).sendMessage(nodes.single().id, SyncProtocol.ARM, SyncProtocol.bytes(json)).await()
        return "전송됨 · 워치의 예약 완료 응답을 확인하세요."
    }
    suspend fun refresh() {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        check(nodes.isNotEmpty()) { "연결된 워치가 없습니다. 저장된 기록을 표시합니다." }
        nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, SyncProtocol.REFRESH, byteArrayOf()).await() }
        val items = Wearable.getDataClient(context).dataItems.await()
        try { items.forEach { ingest(it) } } finally { items.release() }
        SettingsStore(context).reports().filter { it.details.feedbackAt > 0 }.forEach {
            sendFeedback(it.id, it.details.refreshed, it.details.sleepiness, it.details.feedbackAt)
        }
        val summary = SettingsStore(context).sleepContext()
        if (summary.recordedAt > 0) sendContext(summary)
    }
    suspend fun sendContext(summary: SleepContext) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        check(nodes.size == 1) { "연결된 워치가 1대여야 합니다. 요약은 로컬에 저장되어 있습니다." }
        val j = JSONObject().put("version", 1).put("sentAt", System.currentTimeMillis()).put("context", JsonCodec.context(summary))
        Wearable.getMessageClient(context).sendMessage(nodes.single().id, SyncProtocol.CONTEXT, SyncProtocol.bytes(j)).await()
    }
    suspend fun sendFeedback(id: String, refreshed: Int, sleepiness: Int, at: Long) {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        check(nodes.size == 1) { "평가는 로컬에 저장됐습니다. 워치 연결 후 기록 새로고침으로 재전송하세요." }
        val j = JSONObject().put("version", 1).put("id", id).put("refreshed", refreshed).put("sleepiness", sleepiness).put("at", at)
        Wearable.getMessageClient(context).sendMessage(nodes.single().id, SyncProtocol.FEEDBACK, SyncProtocol.bytes(j)).await()
    }
    suspend fun publishReports() {
        SettingsStore(context).reports().forEach { r ->
            val request = PutDataMapRequest.create(SyncProtocol.REPORTS + r.id).apply {
                dataMap.putInt("version", 1)
                dataMap.putString("report", JsonCodec.report(r).toString())
            }.asPutDataRequest()
            Wearable.getDataClient(context).putDataItem(request).await()
        }
    }
    fun ingest(item: DataItem) {
        if (item.uri.path?.startsWith(SyncProtocol.REPORTS) != true) return
        runCatching {
            val map = DataMapItem.fromDataItem(item).dataMap
            require(map.getInt("version") == 1)
            val r = JsonCodec.report(JSONObject(map.getString("report") ?: return))
            require(item.uri.lastPathSegment == r.id)
            SettingsStore(context).putReport(r)
        }
    }
    suspend fun deleteSyncedReports() {
        Wearable.getDataClient(context).deleteDataItems(Uri.parse("wear://*${SyncProtocol.REPORTS}"), DataClient.FILTER_PREFIX).await()
        SettingsStore(context).clearReports()
    }
}
