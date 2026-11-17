package dev.sleepalarm.sensors

import org.junit.Assert.*
import org.junit.Test

class SensorAccessPolicyTest {
    @Test fun `foreground permission must come first even with known installation restriction`() {
        val access = SensorAccessPolicy.evaluate(34, false, false, false, true)
        assertEquals(SensorAccessReason.BODY_REQUIRED, access.reason)
        assertFalse(access.canRequestBackground)
        assertTrue(access.explanation.contains("먼저"))
    }
    @Test fun `older OS needs no separate background sensor permission`() {
        assertTrue(SensorAccessPolicy.evaluate(32, true, false, false, null).permitted)
    }
    @Test fun `granted permission takes precedence over stale restriction metadata`() {
        assertTrue(SensorAccessPolicy.evaluate(34, true, true, true, true).permitted)
    }
    @Test fun `first request explains settings and unknown installer status honestly`() {
        val access = SensorAccessPolicy.evaluate(34, true, false, false, null)
        assertEquals(SensorAccessReason.BACKGROUND_REQUIRED, access.reason)
        assertTrue(access.canRequestBackground)
        assertTrue(access.explanation.contains("항상 허용"))
        assertTrue(access.explanation.contains("확인할 수 없습니다"))
    }
    @Test fun `denied or incomplete request is distinct from confirmed installer restriction`() {
        val access = SensorAccessPolicy.evaluate(34, true, false, true, null)
        assertEquals(SensorAccessReason.BACKGROUND_NOT_GRANTED, access.reason)
        assertTrue(access.explanation.contains("거부 또는 설정 미완료"))
        assertTrue(access.canRequestBackground)
    }
    @Test fun `confirmed missing allowlist cannot prompt an impossible permission request`() {
        val access = SensorAccessPolicy.evaluate(34, true, false, true, true)
        assertEquals(SensorAccessReason.INSTALLER_RESTRICTED, access.reason)
        assertFalse(access.permitted)
        assertFalse(access.canRequestBackground)
        assertTrue(access.explanation.contains("우회할 수 없습니다"))
    }
    @Test fun `allowlisted app still requires separate user grant`() {
        val access = SensorAccessPolicy.evaluate(34, true, false, true, false)
        assertFalse(access.permitted)
        assertTrue(access.explanation.contains("사용자 백그라운드 허용"))
    }
}
