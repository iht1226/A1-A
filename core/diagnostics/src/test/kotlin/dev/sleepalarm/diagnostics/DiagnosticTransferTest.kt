package dev.sleepalarm.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticTransferTest {
    @Test fun `latest never transmits the old selection and selected has a separate mode`() {
        val token = UUID.randomUUID().toString()
        val latest = DiagnosticRequest(token, null).encode()
        assertFalse(latest.has("id"))
        assertNull(DiagnosticRequest.decode(latest.put("id", "old")).selectedId)
        assertEquals("old", DiagnosticRequest.decode(DiagnosticRequest(token, "old").encode()).selectedId)
    }

    @Test fun `peer acknowledgement and local save are separate and duplicate save is ignored`() {
        val t = DiagnosticTransfer()
        t.begin("request", "watch", "")
        assertTrue(t.advance("request", "watch", DiagnosticTransfer.Stage.SENT, "sent"))
        assertTrue(t.advance("request", "watch", DiagnosticTransfer.Stage.RECEIVED, "acked"))
        assertEquals(DiagnosticTransfer.Stage.RECEIVED, t.state.stage)
        assertFalse(t.save("request", "different", "new") { error("wrong peer") })
        var writes = 0
        assertTrue(t.save("request", "watch", "new") { writes++ })
        assertFalse(t.save("request", "watch", "new") { writes++ })
        assertEquals(1, writes)
        assertEquals("new", t.state.savedId)
        assertEquals(DiagnosticTransfer.Stage.SAVED, t.state.stage)
    }

    @Test fun `missing record failure and timeout can be retried and late replies are ignored`() {
        val t = DiagnosticTransfer()
        t.begin("one", "watch", "")
        t.advance("one", "watch", DiagnosticTransfer.Stage.RECEIVED, "acked")
        t.advance("one", "watch", DiagnosticTransfer.Stage.FAILED, "not found")
        assertEquals("not found", t.state.message)
        t.begin("two", "watch", "")
        t.advance("two", "watch", DiagnosticTransfer.Stage.SENT, "sent")
        t.advance("two", "watch", DiagnosticTransfer.Stage.FAILED, "timeout")
        assertFalse(t.save("two", "watch", "old") { error("timed out") })
        t.begin("retry", "watch", "")
        assertFalse(t.save("one", "watch", "old") { error("late reply") })
        assertTrue(t.save("retry", "watch", "latest") {})
    }

    @Test fun `latest query ignores selected ID and upsert keeps newest snapshot without duplicates`() {
        val c = RuntimeEnvironment.getApplication()
        c.deleteDatabase("sensor-diagnostics-v1.db")
        DiagnosticArchive(c).use { archive ->
            fun row(id: String, start: Long, heartbeat: Long) = JSONObject()
                .put("id", id).put("plannedStart", start).put("heartbeat", heartbeat)
            archive.saveRemote(row("old", 100, 110))
            archive.saveRemote(row("latest", 200, 230))
            archive.saveRemote(row("latest", 200, 220))
            archive.saveRemote(row("latest", 200, 230))
            assertEquals("latest", archive.latest()?.getString("id"))
            assertEquals(230L, archive.get("latest")?.getLong("heartbeat"))
            assertEquals(2, archive.recent().size)
        }
    }
}
