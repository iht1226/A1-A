package dev.sleepalarm.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject

/** One lease per capture segment. Monotonic time measures the observed hold interval. */
internal class CaptureWakeLock(
    private val permitted: () -> Boolean,
    private val create: () -> Lock,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
) {
    interface Lock {
        val held: Boolean
        fun acquire(timeoutMillis: Long)
        fun release()
    }

    private var lock: Lock? = null
    private var started = false
    private var closed = false
    private var startElapsed = 0L
    private var timeout = 0L
    private var acquiredAt = 0L
    private var releasedAt = 0L
    private var duration = 0L
    private var status = "NOT_USED"

    @Synchronized
    fun acquire(remainingMillis: Long) {
        if (closed || started || remainingMillis <= 0) return
        started = true
        if (!permitted()) { status = "PERMISSION_DENIED"; return }
        timeout = remainingMillis.coerceAtMost(75 * 60_000L)
        try {
            val candidate = create()
            lock = candidate
            startElapsed = elapsed()
            candidate.acquire(timeout)
            if (candidate.held) {
                acquiredAt = wall()
                status = "HELD"
            } else status = "ACQUIRE_NOT_HELD"
        } catch (e: RuntimeException) {
            status = "ACQUIRE_ERROR_${e.javaClass.simpleName}"
            runCatching { lock?.takeIf { it.held }?.release() }
        }
    }

    @Synchronized
    fun release() {
        if (closed) return
        closed = true
        inspect()
        val current = lock ?: return
        if (status == "HELD") {
            try {
                current.release()
                finish("RELEASED")
            } catch (e: RuntimeException) {
                if (runCatching { !current.held }.getOrDefault(false)) finish("TIMEOUT_OR_OS_RELEASE_OBSERVED")
                else status = "RELEASE_ERROR_${e.javaClass.simpleName}"
            }
        }
    }

    private fun finish(reason: String) {
        releasedAt = wall()
        duration = (elapsed() - startElapsed).coerceIn(0L, timeout)
        status = reason
    }

    private fun inspect() {
        if (status == "HELD" && lock?.held == false) finish("TIMEOUT_OR_OS_RELEASE_OBSERVED")
    }

    @Synchronized
    fun snapshot(): JSONObject {
        inspect()
        val currentDuration = if (status == "HELD" || status.startsWith("RELEASE_ERROR"))
            (elapsed() - startElapsed).coerceIn(0L, timeout) else duration
        return JSONObject().put("status", status).put("acquiredAt", acquiredAt)
            .put("releasedAt", releasedAt).put("heldMs", currentDuration).put("timeoutMs", timeout)
    }

    companion object {
        @SuppressLint("Wakelock") // The lease releases explicitly, with a framework timeout as a second bound.
        fun forContext(context: Context) = CaptureWakeLock(
            permitted = { context.checkSelfPermission(Manifest.permission.WAKE_LOCK) == PackageManager.PERMISSION_GRANTED },
            create = {
                val wake = context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:MotionCapture")
                wake.setReferenceCounted(false)
                object : Lock {
                    override val held get() = wake.isHeld
                    override fun acquire(timeoutMillis: Long) = wake.acquire(timeoutMillis)
                    override fun release() = wake.release()
                }
            },
        )
    }
}
