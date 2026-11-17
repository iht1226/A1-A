package dev.sleepalarm.model

/** Pure alarm policy shared by the Android receiver and JVM regression tests. */
object AlarmTransitions {
    fun smart(p: AlarmPlan, id: String, now: Long, nonce: String): AlarmPlan? {
        if (!p.enabled || p.id != id || !p.settings.smartEnabled || p.earlyDelivered || p.finalDelivered ||
            p.ringing || now < p.windowStart || now >= p.deadline) return null
        return p.copy(earlyDelivered = true, ringNonce = nonce, ringKind = RingKind.SMART)
    }

    fun final(p: AlarmPlan, id: String, at: Long, now: Long, nonce: String): AlarmPlan? {
        if (!p.enabled || p.id != id || p.finalDelivered || at != p.deadline || now < at) return null
        return p.copy(finalDelivered = true, snoozeAt = 0, ringNonce = nonce, ringKind = RingKind.FINAL)
    }

    fun snoozeDelivery(p: AlarmPlan, id: String, at: Long, now: Long, nonce: String): AlarmPlan? {
        if (!p.enabled || p.id != id || p.snoozeAt == 0L || p.snoozeAt != at || now < at) return null
        return p.copy(snoozeAt = 0, ringNonce = nonce, ringKind = RingKind.SNOOZE)
    }

    fun snooze(p: AlarmPlan, nonce: String, now: Long): AlarmPlan? {
        if (!p.enabled || !p.ringing || p.ringNonce != nonce) return null
        return p.copy(snoozeAt = now + p.settings.snoozeMinutes * 60_000L, ringNonce = "", ringKind = null)
    }

    fun dismiss(p: AlarmPlan, nonce: String?): AlarmPlan? {
        if (nonce != null && (!p.ringing || p.ringNonce != nonce)) return null
        return p.copy(enabled = false, ringNonce = "", ringKind = null, snoozeAt = 0)
    }
}
