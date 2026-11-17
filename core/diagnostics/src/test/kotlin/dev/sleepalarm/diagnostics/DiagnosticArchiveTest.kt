package dev.sleepalarm.diagnostics

import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DiagnosticArchiveTest {
    @Before fun setup() { RuntimeEnvironment.getApplication().deleteDatabase("sensor-diagnostics-v1.db") }
    @Test fun `unclean termination keeps stop unknown and includes the entire unobserved tail`() {
        val now=System.currentTimeMillis(); val start=now-120_000
        DiagnosticArchive(RuntimeEnvironment.getApplication()).use { a ->
            a.save(JSONObject().put("id","night").put("plannedStart",start).put("windowStart",start+60_000).put("plannedEnd",now-1)
                .put("heartbeat",start+30_000).put("spans",JSONArray().put(JSONArray(listOf(start,start+30_000)))))
            val j=a.get("night")!!
            assertEquals(0,j.optLong("stoppedAt")); assertTrue(j.getString("stopReason").contains("미확인"))
            assertEquals(30_000,j.getJSONObject("warmup").getLong("observed"))
            assertEquals(0,j.getJSONObject("smart").getLong("observed"))
            assertTrue(j.getLong("maxGap")>=89_000)
        }
    }
    @Test fun `sharing excludes raw spans personalization and preserves a remote snapshot`() {
        val now=System.currentTimeMillis()
        DiagnosticArchive(RuntimeEnvironment.getApplication()).use { a ->
            val j=JSONObject().put("id","n").put("plannedStart",now-1000).put("windowStart",now-500).put("plannedEnd",now+1000)
                .put("profile",JSONObject().put("private","ref")).put("spans",JSONArray()).put("coverage",0.4)
            a.save(j); a.event("n","START")
            val shared=a.shareable(j)
            assertFalse(shared.has("spans")); assertFalse(shared.has("profile")); assertTrue(shared.getBoolean("remoteSnapshot"))
            assertEquals(1,shared.getJSONArray("events").length())
            a.save(shared)
            assertEquals(1,a.shareable(a.get("n")!!).getJSONArray("events").length())
        }
    }
    @Test fun `restart events evaluation snapshots and bounded retention survive reopening`() {
        val app=RuntimeEnvironment.getApplication()
        DiagnosticArchive(app).use { a ->
            a.save(JSONObject().put("id","n").put("plannedStart",System.currentTimeMillis()).put("plannedEnd",System.currentTimeMillis()+60_000))
            a.event("n","SERVICE_START"); a.event("n","SERVICE_RESTART")
            a.tick("n",JSONObject().put("now",123).put("generation",2).put("motionIds",JSONArray(listOf(5000,10000))))
        }
        DiagnosticArchive(app).use { a ->
            assertEquals(2,a.events("n").length()); assertEquals(2,a.ticks("n").single().getInt("generation"))
            assertEquals(2,a.ticks("n").single().getJSONArray("motionIds").length())
        }
    }
}
