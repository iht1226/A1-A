package dev.sleepalarm.update

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.sleepalarm.settings.SettingsStore

object UpdateNotice {
    fun show(c: Context, title: String, text: String, approval: PendingIntent? = null) {
        runCatching {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("app-updates-v1", "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
            val open = approval ?: PendingIntent.getActivity(c, 1200, Intent(c, UpdateActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(1200, NotificationCompat.Builder(c, "app-updates-v1").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

object UpdateInstaller {
    @Volatile var confirmation: PendingIntent? = null
    fun ensureSafe(c: Context) {
        if (UpdateRepository(c).isWatch()) check(canInstall(SettingsStore(c).plan(), System.currentTimeMillis())) {
            "알람 울림·다시 알림·측정 임박 구간에는 업데이트하지 않습니다. 기상 후 알람을 해제하고 다시 시도하세요."
        }
    }
    fun canInstall(p: dev.sleepalarm.model.AlarmPlan?, now: Long): Boolean = p == null || !p.enabled ||
        (!p.ringing && p.snoozeAt == 0L && now < p.sensorStart - 15 * 60_000)

    fun request(c: Context) = synchronized(UpdateRepository.LOCK) {
        val repo = UpdateRepository(c)
        val m = repo.prepared() ?: error("다운로드된 업데이트가 없습니다.")
        ensureSafe(c)
        check(c.packageManager.canRequestPackageInstalls()) { "이 앱의 외부 앱 설치가 허용되지 않았습니다. 설치 허용 화면이 없는 Wear OS에서는 ADB/Play 배포가 필요합니다." }
        val a = if (repo.isWatch()) m.wear else m.phone
        val file = repo.file(a.module); repo.verify(file, a)
        val installer = c.packageManager.packageInstaller
        val old = repo.prefs.getInt("session", -1)
        if (old >= 0) runCatching { installer.abandonSession(old) }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(c.packageName); setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        confirmation = null
        c.getSystemService(NotificationManager::class.java).cancel(1200)
        val id = installer.createSession(params)
        repo.prefs.edit().putInt("session", id).apply()
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                val result = Intent(c, InstallResultReceiver::class.java).setAction("dev.sleepalarm.INSTALL_RESULT").putExtra("expectedSession", id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                synchronized(SettingsStore.LOCK) {
                    ensureSafe(c)
                    session.commit(PendingIntent.getBroadcast(c, id, result, flags).intentSender)
                }
            }
            repo.status("OS에 설치 승인을 요청했습니다. 알림 또는 ‘설치 승인 화면’ 버튼을 확인하세요. OS 응답 전에는 완료가 아닙니다.")
        } catch (e: Exception) { runCatching { installer.abandonSession(id) }; throw e }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val repo = UpdateRepository(c)
        if (intent.getIntExtra("expectedSession", -1) != repo.prefs.getInt("session", -2)) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val approval = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (approval == null) { repo.status("OS 설치 승인 화면을 받지 못했습니다. 설치 기능 지원 여부를 확인하세요."); return }
                val route = Intent(c, UpdateActivity::class.java).putExtra("approval", approval)
                val pi = PendingIntent.getActivity(c, 1201, route, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                UpdateInstaller.confirmation = pi
                repo.status("사용자 설치 승인 대기 중. 알림 또는 설치 승인 화면 버튼을 누르세요.")
                UpdateNotice.show(c, "업데이트 설치 승인", "눌러서 Android 설치 화면에서 승인하세요.", pi)
            }
            PackageInstaller.STATUS_SUCCESS -> { UpdateInstaller.confirmation = null; repo.status("설치 성공: Android PackageInstaller가 성공을 반환했습니다."); repo.prefs.edit().remove("session").apply() }
            else -> {
                UpdateInstaller.confirmation = null
                repo.status("설치 실패/취소: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(300)}. 워치 외부 설치 미지원·서명·공간·사용자 취소를 확인하세요.")
                repo.prefs.edit().remove("session").apply()
            }
        }
    }
}
