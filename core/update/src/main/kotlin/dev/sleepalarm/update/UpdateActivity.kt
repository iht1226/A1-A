package dev.sleepalarm.update

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import androidx.core.content.FileProvider

/** Deliberately uses platform views so the same approval/diagnostics flow works on phone and watch. */
class UpdateActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var approve: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val repo = UpdateRepository(this@UpdateActivity)
            status.text = repo.prefs.getString("status", "아직 확인하지 않았습니다.") + "\n" + repo.prefs.getString("watchStatus", "")
            approve.isEnabled = UpdateInstaller.confirmation != null
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val repo = UpdateRepository(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (32 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
        }
        setContentView(scroll)
        fun text(s: String) = TextView(this).also { it.text = s; it.setPadding(0, 12, 0, 12); column.addView(it) }
        fun button(s: String, click: () -> Unit) = Button(this).also { b -> b.text = s; b.isAllCaps = false; b.setOnClickListener { runCatching(click).onFailure { repo.status(it.message ?: it.javaClass.simpleName) } }; column.addView(b) }
        val info = repo.installed()
        val installer = runCatching { packageManager.getInstallSourceInfo(packageName).installingPackageName }.getOrNull() ?: "확인 불가/ADB"
        text("수면 알람 ${info.versionName} (${info.longVersionCode})\nAPI ${Build.VERSION.SDK_INT}\n설치자: $installer\n현재 서명 SHA-256:\n${repo.signers(info).joinToString("\n")}")
        text("APK는 고정된 GitHub 저장소에서 받고 현재 앱과 같은 서명만 허용합니다. 자동 삭제·무승인 설치는 하지 않습니다. 이전 임시 서명과 다른 키로는 덮어쓸 수 없습니다.")
        status = text("")
        if (!repo.isWatch()) {
            val automatic = CheckBox(this).apply {
                text = "Wi-Fi에서 자동 확인·다운로드 (약 12시간 간격)"
                isChecked = repo.prefs.getBoolean("automatic", false)
                setOnCheckedChangeListener { _, checked -> UpdateWork.automatic(this@UpdateActivity, checked) }
            }
            column.addView(automatic)
            button("지금 확인·APK 다운로드") { UpdateWork.enqueue(this) }
            button("준비된 APK를 워치로 전송") { UpdateWork.enqueue(this, "send") }
            button("워치 APK 공유 · 수동 설치 대안") {
                val manifest = repo.prepared() ?: error("먼저 다운로드하세요.")
                Thread {
                    runCatching {
                        repo.verify(repo.file("wear"), manifest.wear)
                        val uri = FileProvider.getUriForFile(this, "$packageName.updates", repo.file("wear"))
                        runOnUiThread { runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/vnd.android.package-archive"; putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "워치 APK 공유")) }.onFailure { repo.status("공유할 앱을 찾을 수 없습니다.") } }
                    }.onFailure { repo.status(it.message ?: "파일 확인 실패") }
                }.start()
            }
        }
        text(if (repo.isWatch()) "Wear OS가 외부 설치 허용/설치 승인 화면을 제공하지 않으면 이 경로로 설치할 수 없습니다. 이 경우 휴대전화의 APK 공유 + ADB 설치 또는 Play 배포가 필요합니다. 알람 측정 임박·울림·다시 알림 중에는 업데이트를 차단합니다."
            else "다운로드 후 워치 전송을 먼저 요청하고 휴대전화 설치를 승인하세요. Data Layer는 양쪽 앱의 동일 패키지·서명이 필요합니다. OS 절전 정책에 따라 자동 확인은 지연될 수 있습니다.")
        button("이 앱의 외부 설치 허용 설정") { open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) }
        button(if (repo.isWatch()) "워치 업데이트 설치 요청" else "휴대전화 업데이트 설치 요청") {
            Thread { runCatching { UpdateInstaller.request(this) }.onFailure { repo.status(it.message ?: "설치 요청 실패") } }.start()
        }
        approve = button("설치 승인 화면 열기") { UpdateInstaller.confirmation?.send() ?: error("승인 요청이 없습니다. 설치 요청을 다시 시도하세요.") }
        button("업데이트 알림 허용") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 501)
            else open(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        button("닫기") { finish() }
        if (state == null) {
            @Suppress("DEPRECATION")
            val approval = intent.getParcelableExtra<Intent>("approval")
            if (approval != null) runCatching { UpdateInstaller.ensureSafe(this); startActivity(approval) }
                .onFailure { repo.status("설치 승인 화면을 열지 못했습니다: ${it.message ?: it.javaClass.simpleName}") }
        }
    }
    private fun open(action: String) {
        val repo = UpdateRepository(this)
        for (candidate in listOf(action, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS).distinct()) {
            val intent = Intent(candidate)
            if (candidate != Settings.ACTION_SETTINGS) intent.data = Uri.parse("package:$packageName")
            if (candidate == Settings.ACTION_APP_NOTIFICATION_SETTINGS) intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (runCatching { startActivity(intent) }.isSuccess) {
                if (candidate != action) repo.status("전용 설정 화면이 없어 대체 설정을 열었습니다. 지원하지 않으면 ADB/Play 배포가 필요합니다.")
                return
            }
        }
        repo.status("이 기기는 해당 설정 화면을 제공하지 않거나 접근을 차단했습니다. 워치 설정에서 직접 확인하세요. 외부 설치 미지원이면 ADB/Play 배포가 필요합니다.")
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
