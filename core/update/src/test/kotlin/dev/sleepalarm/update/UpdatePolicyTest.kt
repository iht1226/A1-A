package dev.sleepalarm.update

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageInstaller
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatePolicyTest {
    private val url = "https://github.com/iht1226/A1-A/releases/download/v1/wear-update.apk"
    private fun asset(module: String) = ApkAsset(module, 8, "a".repeat(64), 30, url)
    private lateinit var app: Application
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        UpdateRepository(app).prefs.edit().clear().commit()
        UpdateInstaller.confirmation = null
    }
    private fun allowed(old: Long = 7, next: Long = 8, oldCert: Set<String> = setOf("A"), newCert: Set<String> = setOf("A"),
        pkg: String = "dev.sleepalarm.app", watch: Boolean = true, expected: Boolean = true) =
        UpdatePolicy.compatible(old, next, oldCert, newCert, pkg, watch, expected)
    @Test fun `only higher version with identical known signer and matching device is allowed`() {
        assertTrue(allowed()); assertFalse(allowed(next = 7)); assertFalse(allowed(next = 6))
        assertFalse(allowed(newCert = setOf("B"))); assertFalse(allowed(oldCert = emptySet(), newCert = emptySet()))
        assertFalse(allowed(newCert = setOf("A", "B"))); assertFalse(allowed(pkg = "other.app"))
        assertFalse(allowed(watch = false)); assertFalse(allowed(expected = false))
    }
    @Test fun `APK transfer cannot truncate or exceed the declared size`() {
        val sink = ByteArrayOutputStream()
        UpdatePolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1,2,3)), sink, 3)
        assertArrayEquals(byteArrayOf(1,2,3), sink.toByteArray())
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1,2)), ByteArrayOutputStream(), 3) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.copyBounded(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream(), 3) }
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.copyBounded(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), MAX_APK_BYTES + 1) }
    }
    @Test fun `repository host scheme and traversal are restricted`() {
        requireReleaseUrl(url)
        listOf(url.replace("https:", "http:"), url.replace("github.com", "github.com.evil.test"),
            url.replace("iht1226", "attacker"), url.replace("/v1/", "/../"), url.replace("/v1/", "/%2e%2e/"),
            url.replace("github.com/", "user@github.com/"), "$url#fragment").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { requireReleaseUrl(bad) }
        }
    }
    @Test fun `manifest must contain exactly matching phone and watch versions`() {
        val good = UpdateManifest("0.3.0", asset("phone"), asset("wear"))
        assertEquals(good, UpdateManifest.parse(good.json()))
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(good.copy(wear = asset("wear").copy(versionCode = 9)).json()) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(good.copy(wear = asset("phone")).json()) }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(good.copy(wear = asset("wear").copy(sha256 = "bad")).json()) }
    }
    @Test fun `watch installation blocks at safety boundary during ringing and snooze`() {
        val p = dev.sleepalarm.model.AlarmPlan("night", 1, 10_000_000, dev.sleepalarm.model.AlarmSettings())
        val boundary = p.sensorStart - 15 * 60_000
        assertTrue(UpdateInstaller.canInstall(p, boundary - 1))
        assertFalse(UpdateInstaller.canInstall(p, boundary))
        assertFalse(UpdateInstaller.canInstall(p.copy(ringNonce = "ring"), 2))
        assertFalse(UpdateInstaller.canInstall(p.copy(snoozeAt = 100), 2))
        assertTrue(UpdateInstaller.canInstall(p.copy(enabled = false), boundary))
        assertTrue(UpdateInstaller.canInstall(null, boundary))
    }
    @Test fun `pending user action posts approval and does not claim installation succeeded`() {
        val repo = UpdateRepository(app); repo.prefs.edit().putInt("session", 15).commit()
        InstallResultReceiver().onReceive(app, Intent().putExtra("expectedSession", 15)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("test.confirm")))
        assertNotNull(UpdateInstaller.confirmation)
        assertTrue(repo.prefs.getString("status", "")!!.contains("승인 대기"))
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun `stale session result cannot overwrite active update`() {
        val repo = UpdateRepository(app); repo.prefs.edit().putInt("session", 16).commit(); repo.status("active")
        InstallResultReceiver().onReceive(app, Intent().putExtra("expectedSession", 15).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        assertEquals("active", repo.prefs.getString("status", ""))
    }
    @Test fun `cancellation is failure and only explicit OS success is success`() {
        val repo = UpdateRepository(app)
        repo.prefs.edit().putInt("session", 16).commit()
        InstallResultReceiver().onReceive(app, Intent().putExtra("expectedSession", 16).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED))
        assertTrue(repo.prefs.getString("status", "")!!.contains("실패/취소"))
        repo.prefs.edit().putInt("session", 17).commit()
        InstallResultReceiver().onReceive(app, Intent().putExtra("expectedSession", 17).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        assertTrue(repo.prefs.getString("status", "")!!.contains("설치 성공"))
    }
}
