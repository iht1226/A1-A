package dev.sleepalarm.sensors

import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureWakeLockTest {
    @Before fun grantManifestPermissionInTestSandbox() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.WAKE_LOCK)
    }

    @Test fun `official partial lock is held and released without waking display`() {
        val app = RuntimeEnvironment.getApplication()
        val power = app.getSystemService(PowerManager::class.java)
        shadowOf(power).setIsInteractive(false)
        val lease = CaptureWakeLock.forContext(app)
        lease.acquire(600_000)
        assertEquals("HELD", lease.snapshot().getString("status"))
        assertTrue(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse(power.isInteractive)
        lease.release()
        lease.release()
        assertFalse(org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock().isHeld)
        assertEquals("RELEASED", lease.snapshot().getString("status"))
    }

    @Test fun `framework timeout releases the partial lock without explicit cleanup`() {
        val lease = CaptureWakeLock.forContext(RuntimeEnvironment.getApplication())
        lease.acquire(1_000)
        val wake = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1_010))
        assertFalse(wake.isHeld)
        assertEquals("TIMEOUT_OR_OS_RELEASE_OBSERVED", lease.snapshot().getString("status"))
        assertTrue(lease.snapshot().getLong("heldMs") <= 1_000)
        lease.release()
    }

    @Test fun `duplicate start and stop keep the original timeout and monotonic duration`() {
        val lock = FakeLock()
        var now = 100L
        val lease = CaptureWakeLock({ true }, { lock }, { now }, { now + 1_000 })
        lease.acquire(600_000)
        lease.acquire(900_000)
        now += 200
        lease.release()
        lease.release()
        lease.acquire(600_000)
        assertEquals(600_000L, lock.timeout)
        assertEquals(1, lock.releases)
        assertEquals(200L, lease.snapshot().getLong("heldMs"))
    }

    @Test fun `permission rejection and acquire failure leave no held lock`() {
        val denied = CaptureWakeLock({ false }, { error("must not create") })
        denied.acquire(600_000)
        assertEquals("PERMISSION_DENIED", denied.snapshot().getString("status"))
        val lock = FakeLock().apply { failAcquire = true }
        val broken = CaptureWakeLock({ true }, { lock })
        broken.acquire(900_000)
        assertFalse(lock.held)
        assertEquals("ACQUIRE_ERROR_SecurityException", broken.snapshot().getString("status"))
    }

    @Test fun `stopped before registration and expired windows cannot acquire`() {
        val lease = CaptureWakeLock({ true }, { error("must not create") })
        lease.acquire(0)
        lease.release()
        lease.acquire(900_000)
        assertEquals("NOT_USED", lease.snapshot().getString("status"))
    }

    private class FakeLock : CaptureWakeLock.Lock {
        override var held = false
        var timeout = 0L
        var releases = 0
        var failAcquire = false
        override fun acquire(timeoutMillis: Long) {
            timeout = timeoutMillis
            held = true
            if (failAcquire) throw SecurityException()
        }
        override fun release() { releases++; held = false }
    }
}
