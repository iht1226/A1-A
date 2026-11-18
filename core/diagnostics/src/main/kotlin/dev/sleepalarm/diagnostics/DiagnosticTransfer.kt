package dev.sleepalarm.diagnostics

import org.json.JSONObject
import java.util.UUID

internal data class DiagnosticRequest(val token: String, val selectedId: String?) {
    fun encode() = JSONObject().put("token", token).put("mode", if (selectedId == null) "latest" else "selected").apply {
        if (selectedId != null) put("id", selectedId)
    }

    companion object {
        fun decode(json: JSONObject): DiagnosticRequest {
            val token = json.getString("token")
            UUID.fromString(token)
            val selected = when (json.getString("mode")) {
                "latest" -> null
                "selected" -> json.getString("id").also { require(it.isNotBlank() && it.length <= 100) }
                else -> error("지원하지 않는 진단 요청입니다.")
            }
            return DiagnosticRequest(token, selected)
        }
    }
}

internal class DiagnosticTransfer {
    enum class Stage { IDLE, REQUESTING, SENT, RECEIVED, SAVED, FAILED }

    data class State(val token: String = "", val node: String = "", val stage: Stage = Stage.IDLE,
                     val message: String = "", val savedId: String = "")

    @Volatile var state = State()
        private set

    @Synchronized fun begin(token: String, node: String, message: String) {
        state = State(token, node, Stage.REQUESTING, message)
    }

    @Synchronized fun bind(token: String, node: String): Boolean {
        if (!pending(token, "")) return false
        state = State(token, node, Stage.REQUESTING, "진단 전송 요청 중")
        return true
    }

    @Synchronized fun unsolicitedSaved(id: String) {
        if (state.stage in setOf(Stage.REQUESTING, Stage.SENT, Stage.RECEIVED)) return
        state = State(stage = Stage.SAVED, message = "워치가 보낸 진단 저장 완료", savedId = id)
    }

    @Synchronized fun pending(token: String, node: String): Boolean =
        token == state.token && node == state.node && state.stage in setOf(Stage.REQUESTING, Stage.SENT, Stage.RECEIVED)

    @Synchronized
    fun advance(token: String, node: String, stage: Stage, message: String, id: String = ""): Boolean {
        if (!pending(token, node) || stage <= state.stage) return false
        state = State(token, node, stage, message, id)
        return true
    }

    /** Holding the session lock prevents a replaced request from committing a stale response. */
    @Synchronized
    fun save(token: String, node: String, id: String, write: () -> Unit): Boolean {
        if (!pending(token, node)) return false
        write()
        return advance(token, node, Stage.SAVED, "휴대전화 저장 완료", id)
    }
}
