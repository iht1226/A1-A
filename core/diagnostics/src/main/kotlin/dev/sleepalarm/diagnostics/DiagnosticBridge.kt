package dev.sleepalarm.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import org.json.*
import java.util.concurrent.TimeUnit

class DiagnosticBridge : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            val nearby = Tasks.await(Wearable.getNodeClient(this).connectedNodes, 5, TimeUnit.SECONDS).any { it.id == event.sourceNodeId && it.isNearby }
            if (!nearby || event.data.size > 90_000) return
            if (event.path == REQUEST && packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
                send(this, false, event.data.toString(Charsets.UTF_8))
            else if (event.path == RESPONSE && !packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
                val a = JSONArray(event.data.toString(Charsets.UTF_8))
                require(a.length() <= 5)
                DiagnosticArchive(this).use { db -> for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i); require(j.getString("id").length <= 100 && j.has("plannedStart")); db.save(j)
                } }
            }
        }
    }
    companion object {
        private const val REQUEST = "/sleep/diagnostics/request"
        private const val RESPONSE = "/sleep/diagnostics/response"
        /** Nearby-only MessageClient, no DataItem asset or cloud-backed diagnostic replication. */
        fun send(c: Context, request: Boolean, id: String = "") {
            val nodes = Tasks.await(Wearable.getNodeClient(c).connectedNodes, 5, TimeUnit.SECONDS).filter { it.isNearby }
            check(nodes.isNotEmpty()) { "근처 휴대전화/워치 연결이 필요합니다. 원격 서버에는 진단을 올리지 않습니다." }
            val payload = if (request) id.toByteArray() else DiagnosticArchive(c).use { db ->
                val candidates = if (id.isNotBlank()) listOfNotNull(db.get(id)) else db.recent().take(5)
                val out = JSONArray()
                candidates.forEach { j ->
                    val share = db.shareable(j)
                    if (out.toString().toByteArray().size + share.toString().toByteArray().size < 85_000) out.put(share)
                }
                out.toString().toByteArray()
            }
            nodes.forEach { Tasks.await(Wearable.getMessageClient(c).sendMessage(it.id, if (request) REQUEST else RESPONSE, payload), 5, TimeUnit.SECONDS) }
        }
    }
}
