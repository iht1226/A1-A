package dev.sleepalarm.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class DiagnosticBridge : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            if (event.data.size > MAX_BYTES || nearby(this).none { it.id == event.sourceNodeId }) return
            val json = JSONObject(event.data.toString(Charsets.UTF_8))
            val node = event.sourceNodeId
            val token = json.getString("token")
            UUID.fromString(token)
            val watch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
            when {
                watch && event.path == REQUEST -> respond(node, DiagnosticRequest.decode(json))
                watch && event.path == PUSH_RESULT -> transfer.advance(token, node,
                    if (json.optBoolean("ok")) DiagnosticTransfer.Stage.SAVED else DiagnosticTransfer.Stage.FAILED,
                    if (json.optBoolean("ok")) "휴대전화 저장 완료 확인" else json.optString("error"))
                !watch && event.path == ACK -> transfer.advance(token, node, DiagnosticTransfer.Stage.RECEIVED, "워치가 요청을 받았습니다. 진단 응답 대기 중")
                !watch && event.path == RESULT -> receiveResult(node, token, json)
                !watch && event.path == PUSH -> receivePush(node, token, json)
                else -> Unit
            }
        }.onFailure { Log.w("SleepAlarm", "Nearby diagnostic message failed", it) }
    }

    private fun respond(node: String, request: DiagnosticRequest) {
        val token = request.token
        runCatching { send(this, node, ACK, envelope(token)) }
        val response = runCatching { envelope(token).put("record", record(this, request.selectedId)) }
            .getOrElse { envelope(token).put("error", it.message ?: "워치에서 진단을 읽지 못했습니다.") }
        send(this, node, RESULT, response)
    }

    private fun receiveResult(node: String, token: String, json: JSONObject) {
        if (json.has("error")) {
            transfer.advance(token, node, DiagnosticTransfer.Stage.FAILED, json.getString("error"))
            return
        }
        runCatching {
            val row = json.getJSONObject("record")
            transfer.save(token, node, row.getString("id")) { storeRemote(row) }
        }.onFailure {
            transfer.advance(token, node, DiagnosticTransfer.Stage.FAILED, "휴대전화에 진단을 저장하지 못했습니다. 다시 가져오세요.")
        }
    }

    private fun receivePush(node: String, token: String, json: JSONObject) {
        val row = json.getJSONObject("record")
        val ok = runCatching { storeRemote(row) }.isSuccess
        send(this, node, PUSH_RESULT, envelope(token).put("ok", ok).apply {
            if (!ok) put("error", "휴대전화에 진단을 저장하지 못했습니다.")
        })
        if (ok) transfer.unsolicitedSaved(row.getString("id"))
    }

    private fun storeRemote(row: JSONObject) {
        require(row.getString("id").length in 1..100 && row.getLong("plannedStart") > 0)
        row.remove("spans")
        row.remove("profile")
        row.put("remoteSnapshot", true)
        DiagnosticArchive(this).use { it.saveRemote(row) }
    }

    companion object {
        private const val PREFIX = "/sleep/diagnostics/v2"
        private const val REQUEST = "$PREFIX/request"
        private const val ACK = "$PREFIX/ack"
        private const val RESULT = "$PREFIX/result"
        private const val PUSH = "$PREFIX/push"
        private const val PUSH_RESULT = "$PREFIX/push-result"
        private const val MAX_BYTES = 90_000
        internal val transfer = DiagnosticTransfer()

        fun request(c: Context, selectedId: String?) {
            if (selectedId != null) require(selectedId.isNotBlank()) { "가져올 기록을 먼저 선택하세요." }
            start(c, REQUEST) { token -> DiagnosticRequest(token, selectedId).encode() }
        }

        fun push(c: Context, selectedId: String) {
            require(selectedId.isNotBlank()) { "보낼 기록을 먼저 선택하세요." }
            start(c, PUSH) { token -> envelope(token).put("record", record(c, selectedId)) }
        }

        private fun start(c: Context, path: String, payload: (String) -> JSONObject) {
            val token = UUID.randomUUID().toString()
            transfer.begin(token, "", "근거리 연결 확인 중")
            var node = ""
            try {
                node = nearby(c).sortedBy { it.id }.firstOrNull()?.id
                    ?: error("근처 기기 연결이 없습니다. Bluetooth 연결 후 다시 시도하세요.")
                if (!transfer.bind(token, node)) return
                send(c, node, path, payload(token))
                transfer.advance(token, node, DiagnosticTransfer.Stage.SENT,
                    if (path == REQUEST) "요청 전송됨. 워치 수신 대기 중" else "진단 전송됨. 휴대전화 저장 확인 대기 중")
                val peer = node
                Handler(Looper.getMainLooper()).postDelayed({
                    val message = if (transfer.state.stage == DiagnosticTransfer.Stage.RECEIVED)
                        "워치가 요청을 받았지만 진단 응답이 없습니다."
                    else "상대 기기의 응답이 없습니다."
                    transfer.advance(token, peer, DiagnosticTransfer.Stage.FAILED, "$message 양쪽 앱 버전과 근거리 연결을 확인하고 다시 시도하세요.")
                }, 20_000)
            } catch (e: Exception) {
                // If node lookup failed the session still uses an empty peer ID.
                if (!transfer.advance(token, node, DiagnosticTransfer.Stage.FAILED, e.message ?: "전송 실패. 연결 후 다시 시도하세요."))
                    transfer.advance(token, "", DiagnosticTransfer.Stage.FAILED, e.message ?: "전송 실패. 다시 시도하세요.")
            }
        }

        private fun envelope(token: String) = JSONObject().put("token", token)

        private fun record(c: Context, selectedId: String?): JSONObject = DiagnosticArchive(c).use { archive ->
            val row = if (selectedId == null) archive.latest() else archive.get(selectedId)
            checkNotNull(row) {
                if (selectedId == null) "워치에 저장된 진단이 없습니다." else "워치에서 선택한 진단을 찾을 수 없습니다 ($selectedId). 최신 진단 가져오기를 시도하세요."
            }
            archive.shareable(row)
        }

        private fun nearby(c: Context) = Tasks.await(Wearable.getNodeClient(c).connectedNodes, 5, TimeUnit.SECONDS).filter { it.isNearby }

        private fun send(c: Context, node: String, path: String, json: JSONObject) {
            val data = json.toString().toByteArray()
            require(data.size <= MAX_BYTES) { "진단이 근거리 메시지 크기 제한을 초과했습니다. 워치에서 텍스트로 공유하세요." }
            check(nearby(c).any { it.id == node }) { "근거리 연결이 끊겼습니다. 연결 후 다시 시도하세요." }
            Tasks.await(Wearable.getMessageClient(c).sendMessage(node, path, data), 5, TimeUnit.SECONDS)
        }
    }
}
