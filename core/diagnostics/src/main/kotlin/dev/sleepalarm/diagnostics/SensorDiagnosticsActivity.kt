package dev.sleepalarm.diagnostics

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*

class SensorDiagnosticsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private lateinit var transferStatus: TextView
    private var shownTransfer = DiagnosticTransfer.State()
    private var refreshTick = 0
    private lateinit var list: LinearLayout
    private var selected = ""
    private var testRequestedAt = 0L
    private var busy = false
    private var rendered = ""
    private var ids = emptyList<String>()
    private val refresh = object : Runnable {
        override fun run() {
            val transfer = DiagnosticBridge.transfer.state
            if (transfer != shownTransfer) {
                transferStatus.text = transfer.message
                if (transfer.stage == DiagnosticTransfer.Stage.SAVED && transfer.savedId.isNotEmpty()) {
                    selected = transfer.savedId
                    load()
                }
                shownTransfer = transfer
            }
            if (refreshTick++ % 5 == 0 && !busy) load()
            handler.postDelayed(this, 1_000)
        }
    }
    private fun message(s: String) { runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() } }
    private fun background(action: () -> Unit) { Thread { runCatching(action).onFailure { message(it.message ?: "실행 실패") } }.start() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); selected = intent.getStringExtra("id").orEmpty()
        val watch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (28 * resources.displayMetrics.density).toInt(); setPadding(p,p,p,p) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(column) })
        fun button(label: String, action: () -> Unit) = Button(this).also { b -> b.text = label; b.isAllCaps = false; b.setOnClickListener { runCatching(action).onFailure { message(it.message ?: "실행 실패") } }; column.addView(b) }
        column.addView(TextView(this).apply { text = "센서 진단 · 화면 꺼짐 시험\n시험은 알람 설정·수면 이력을 변경하지 않습니다. 실기기 검증 성공을 뜻하지 않습니다." })
        if (watch) {
            button("움직임 측정 권한") { requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 900) }
            val cpu = CheckBox(this).apply { text = "이 시험만 CPU 유지 (실험적)" }
            column.addView(cpu)
            listOf(10, 15).forEach { minutes -> button("${minutes}분 수집 시험 시작") {
                testRequestedAt = System.currentTimeMillis()
                startForegroundService(Intent().setClassName(packageName, "dev.sleepalarm.sensors.SmartWindowService")
                    .setAction(DiagnosticArchive.TEST_START).putExtra("minutes", minutes)
                    .putExtra(DiagnosticArchive.CPU_HOLD, cpu.isChecked))
                selected = ""; rendered = ""; message("시험 요청됨. 실제 시작과 등록을 확인하고 화면을 끄세요.")
            } }
            button("수집 시험만 종료") { sendBroadcast(Intent(DiagnosticArchive.TEST_STOP).setPackage(packageName)) }
            button("선택 기록 오프라인 리플레이") { sendBroadcast(Intent(DiagnosticArchive.REPLAY).setPackage(packageName).putExtra("id", selected)) }
        }
        if (watch) button("선택 진단을 근처 휴대전화로 전송") {
            val id = selected
            background { DiagnosticBridge.push(this, id) }
        } else {
            button("최신 진단 가져오기") { background { DiagnosticBridge.request(this, null) } }
            button("현재 선택한 진단 가져오기") {
                val id = selected
                background { DiagnosticBridge.request(this, id) }
            }
        }
        transferStatus = TextView(this).apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(transferStatus)
        button("최근 진단 새로 선택") { selected = ""; rendered = ""; load() }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; column.addView(list)
        text = TextView(this); column.addView(text)
        button("진단 텍스트 공유") {
            val output = rendered.take(85_000)
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, output), "센서 진단 텍스트")) }
                .onFailure { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("센서 진단", output)); message("공유 화면 미지원: 클립보드에 복사했습니다.") }
        }
        button("닫기") { finish() }
    }
    private fun load() {
        busy = true
        val requested = selected
        val requestedTest = testRequestedAt
        background {
            try {
                DiagnosticArchive(this).use { archive ->
                    val all = archive.recent()
                    val test = if (requestedTest > 0) all.firstOrNull { it.optBoolean("test") && it.optLong("plannedStart") >= requestedTest } else null
                    val id = test?.getString("id") ?: requested.ifBlank { all.firstOrNull()?.optString("id").orEmpty() }
                    val j = archive.get(id)?.let(archive::shareable)
                    val display = (if (packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)) dev.sleepalarm.settings.SettingsStore(this).status() + "\n\n" else "") +
                        (j?.let(DiagnosticArchive::text) ?: "진단 없음. 이전 버전의 수집 실패 원인은 새 로그 없이 확정할 수 없습니다.")
                    runOnUiThread {
                        if (isFinishing || isDestroyed || selected != requested || testRequestedAt != requestedTest) return@runOnUiThread
                        selected = id
                        if (test != null) testRequestedAt = 0
                        if (display != rendered) { text.text = display; rendered = display }
                        val currentIds = all.map { it.getString("id") }
                        if (ids != currentIds) {
                            ids = currentIds; list.removeAllViews()
                            all.forEach { row -> list.addView(Button(this).apply {
                                isAllCaps = false
                                text = "${DiagnosticArchive.clock(row.optLong("plannedStart"))} ${if (row.optBoolean("test")) "시험" else "기상"}"
                                setOnClickListener { selected = row.getString("id"); rendered = ""; load() }
                            }) }
                        }
                    }
                }
            } finally { runOnUiThread { busy = false } }
        }
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
