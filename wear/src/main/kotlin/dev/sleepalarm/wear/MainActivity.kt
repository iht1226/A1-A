package dev.sleepalarm.wear

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import dev.sleepalarm.alarm.*
import dev.sleepalarm.model.*
import dev.sleepalarm.sensors.*
import dev.sleepalarm.settings.SettingsStore
import dev.sleepalarm.sync.WatchSync
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

private fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

class MainActivity : ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)
    private val foreground = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++; permissionMessage = "" }
    private val background = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionRevision++
        permissionMessage = SensorPermissionStatus.inspect(this).explanation
    }
    private val motionPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
    private var permissionMessage by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colors = Colors(primary = Color(0xFF9CE5D5), secondary = Color(0xFFBBC4FF))) {
                WatchHome(
                    permission = { which ->
                        runCatching { when (which) {
                            "notification" -> if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else open(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            "motion" -> motionPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            "body" -> foreground.launch(Manifest.permission.BODY_SENSORS)
                            "background" -> {
                                val access = SensorPermissionStatus.inspect(this)
                                if (Build.VERSION.SDK_INT >= 33 && access.canRequestBackground) {
                                    SensorPermissionStatus.markBackgroundRequested(this)
                                    permissionRevision++
                                    runCatching { background.launch(Manifest.permission.BODY_SENSORS_BACKGROUND) }
                                        .onFailure { permissionMessage = "권한 설정을 열 수 없습니다. ‘앱 시스템 설정’ → 권한 → 신체 센서를 확인하세요. ${it.javaClass.simpleName}" }
                                } else permissionMessage = access.explanation
                            }
                            "exact" -> if (Build.VERSION.SDK_INT in 31..32) open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                else permissionMessage = "Android 13 이상은 USE_EXACT_ALARM 설치 권한을 사용합니다. 별도 허용 화면이 없습니다. 아래 API 상태와 설치 버전을 확인하세요."
                            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            else -> open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        } }.onFailure { permissionMessage = "권한 요청 화면을 열 수 없습니다 (${it.javaClass.simpleName}). 워치 설정에서 직접 확인하세요." }
                    },
                )
            }
        }
    }
    override fun onResume() { super.onResume(); permissionRevision++; runCatching { AlarmController(this).restore() } }
    private fun open(action: String) { permissionMessage = SafeSettings.open(this, action) }

    @Composable
    private fun WatchHome(permission: (String) -> Unit) {
        val store = remember { SettingsStore(this) }
        val alarm = remember { AlarmController(this) }
        val scope = rememberCoroutineScope()
        var settings by remember { mutableStateOf(store.settings()) }
        var plan by remember { mutableStateOf(store.plan()) }
        var status by remember { mutableStateOf(store.status()) }
        var reports by remember { mutableStateOf(store.reports()) }
        var message by remember { mutableStateOf("") }
        var tab by remember { mutableIntStateOf(0) }
        var exact by remember { mutableStateOf(alarm.canSchedule()) }
        var notify by remember { mutableStateOf(false) }
        var sensors by remember { mutableStateOf(false) }
        var sensorAccess by remember { mutableStateOf(SensorPermissionStatus.inspect(this)) }
        var alarmError by remember { mutableStateOf(store.alarmError()) }
        LaunchedEffect(permissionRevision) { sensorAccess = SensorPermissionStatus.inspect(this@MainActivity) }
        LaunchedEffect(Unit) {
            while (true) {
                plan = store.plan(); status = store.status(); reports = store.reports()
                exact = alarm.canSchedule()
                notify = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                sensors = MonitoringAccess.canMonitor(this@MainActivity)
                alarmError = store.alarmError()
                delay(1_000)
            }
        }
        fun change(next: AlarmSettings) { settings = next; store.saveSettings(next) }
        fun execute(block: () -> Unit) {
            runCatching(block).onFailure { message = it.message ?: "실행 실패" }
        }
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Text("수면 알람", style = MaterialTheme.typography.title2) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("알람", "설정", "기록").forEachIndexed { i, title ->
                    CompactChip(onClick = { tab = i }, label = { Text(title) },
                        colors = if (tab == i) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors())
                }
            } }
            if (tab == 0) {
                item { Text("%02d:%02d".format(settings.hour, settings.minute), style = MaterialTheme.typography.display2) }
                item { Adjust("시", "${settings.hour}", { change(settings.copy(hour = (settings.hour + 23) % 24)) }, { change(settings.copy(hour = (settings.hour + 1) % 24)) }) }
                item { Adjust("분", "${settings.minute}", { change(settings.copy(minute = (settings.minute + 59) % 60)) }, { change(settings.copy(minute = (settings.minute + 1) % 60)) }) }
                item { Text(if (settings.smartEnabled) "최대 ${settings.windowMinutes}분 먼저 · 근거가 충분할 때만" else "일반 알람", textAlign = TextAlign.Center) }
                item { Chip(onClick = { execute {
                    check(exact) { "정확한 알람 권한을 먼저 허용하세요." }
                    val p = alarm.arm(settings); plan = p; message = "예약됨: ${time(p.deadline)}" + if (!notify) " · 알림 차단으로 해제 화면 등이 제한될 수 있습니다." else ""
                } }, label = { Text("다음 기상 예약") }, modifier = Modifier.fillMaxWidth()) }
                if (plan?.enabled == true) {
                    item { Text("최종 기상 ${time(plan!!.deadline)}\n${if (plan!!.ringing) "알람 울리는 중" else if (plan!!.snoozeAt > 0) "다시 알림 ${time(plan!!.snoozeAt)}" else "워치에 예약됨"}", textAlign = TextAlign.Center) }
                    item { Chip(onClick = { alarm.dismiss(); plan = store.plan(); message = "예약 해제됨" }, label = { Text("예약 해제") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                }
                item { Text("정확한 알람: ${if (exact) "예약 가능" else "예약 불가"} · 알림: ${if (notify) "켜짐" else "꺼짐"}", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { Text(status, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { Text("측정 ${time(plan?.sensorStart ?: settings.nextDeadline(Instant.now(), ZoneId.systemDefault()).toEpochMilli() - (settings.windowMinutes * 60_000L + SensorSchedule.warmupMillis(settings.windowMinutes)))}부터 · 판단은 기상 ${settings.windowMinutes}분 전부터", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                if (!sensors && settings.smartEnabled) item { Text(sensorAccess.explanation, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            }
            if (tab == 1) {
                item { ToggleChip(checked = settings.smartEnabled, onCheckedChange = { change(settings.copy(smartEnabled = it)) },
                    label = { Text("스마트 기상") }, toggleControl = { Switch(checked = settings.smartEnabled) }, modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = { change(settings.copy(algorithmMode = AlgorithmMode.entries[(settings.algorithmMode.ordinal + 1) % AlgorithmMode.entries.size])) }, label = { Text("방식: ${modeLabel(settings.algorithmMode)}") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("교대·무작위 시험은 선택 시에만 적용됩니다. 같은 기상 날짜의 재예약과 재부팅은 배정을 유지합니다.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { ToggleChip(checked = settings.keepFinalAfterSmartDismiss, onCheckedChange = { change(settings.copy(keepFinalAfterSmartDismiss = it)) }, label = { Text("조기 해제 후 정시 백업") }, toggleControl = { Switch(checked = settings.keepFinalAfterSmartDismiss) }, modifier = Modifier.fillMaxWidth()) }
                item { Adjust("보수 기준", "%.2f".format(settings.adaptiveThreshold), { change(settings.copy(adaptiveThreshold = (settings.adaptiveThreshold - 0.02).coerceAtLeast(0.65))) }, { change(settings.copy(adaptiveThreshold = (settings.adaptiveThreshold + 0.02).coerceAtMost(0.90))) }) }
                item { Text("높을수록 조기 기상을 더 보수적으로 판단합니다. 최종 시각은 바뀌지 않습니다.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { Adjust("범위", "${settings.windowMinutes}분", { change(settings.copy(windowMinutes = (settings.windowMinutes - 5).coerceAtLeast(10))) }, { change(settings.copy(windowMinutes = (settings.windowMinutes + 5).coerceAtMost(60))) }) }
                item { Adjust("진동", "${settings.amplitude}", { change(settings.copy(amplitude = (settings.amplitude - 32).coerceAtLeast(1))) }, { change(settings.copy(amplitude = (settings.amplitude + 32).coerceAtMost(255))) }) }
                item { Chip(onClick = { change(settings.copy(pattern = VibrationPattern.entries[(settings.pattern.ordinal + 1) % 3])) }, label = { Text("패턴: ${patternName(settings.pattern)}") }, modifier = Modifier.fillMaxWidth()) }
                item { Adjust("다시 알림", "${settings.snoozeMinutes}분", { change(settings.copy(snoozeMinutes = (settings.snoozeMinutes - 1).coerceAtLeast(1))) }, { change(settings.copy(snoozeMinutes = (settings.snoozeMinutes + 1).coerceAtMost(30))) }) }
                item { Chip(onClick = { execute { AlarmNotifications.vibrate(this@MainActivity, settings, false) } }, label = { Text("진동 미리 보기") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("변경한 설정은 다음 예약에 적용됩니다. 진동 강도는 기기 지원 범위에 따릅니다.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { Text(AlarmAccess.inspect(this@MainActivity).text(), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { Text("스마트 센서 ${if (sensors) "허용됨" else "제한됨"}", textAlign = TextAlign.Center) }
                item { ActionChip("센서 진단 · 화면 꺼짐 시험", { startActivity(Intent(this@MainActivity, dev.sleepalarm.diagnostics.SensorDiagnosticsActivity::class.java)) }) }
                item { ActionChip("앱 버전 · 업데이트", { startActivity(Intent(this@MainActivity, dev.sleepalarm.update.UpdateActivity::class.java)) }) }
                item { Text(sensorAccess.explanation, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { ActionChip("알림 권한", { permission("notification") }) }
                item { ActionChip("정확한 알람 권한", { permission("exact") }) }
                item { ActionChip("움직임 분석 권한", { permission("motion") }) }
                item { Text("움직임 권한만으로도 분석할 수 있습니다. 심박은 선택 보조 지표입니다.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { ActionChip("1. 심박 센서 허용", { permission("body") }) }
                item { ActionChip("2. 센서 항상 허용", { permission("background") }) }
                item { ActionChip("전체 화면 알람 허용", { permission("fullscreen") }) }
                item { ActionChip("앱 시스템 설정", { permission("settings") }) }
                item { ActionChip("2분 뒤 일반 알람 시험", { execute {
                    check(exact) { "정확한 알람 예약 권한이 필요합니다. 알림 권한과 별개입니다." }
                    val p = alarm.armTest(settings)
                    plan = p; message = "시험 예약: ${time(p.deadline)}"
                } }) }
            }
            if (tab == 2) {
                item { Text("자체 기록은 관측·알람 이력입니다. 총 수면 시간과 수면 단계를 측정하지 않습니다.", textAlign = TextAlign.Center) }
                if (reports.isEmpty()) item { Text("아직 기록이 없습니다.") }
                reports.take(7).forEach { r -> item { ActionChip("${time(r.deadline)} 관측 구간 진단", { startActivity(Intent(this@MainActivity, dev.sleepalarm.diagnostics.SensorDiagnosticsActivity::class.java).putExtra("id", r.id)) }) }; item { WatchReport(r) { refreshed, sleepy ->
                    store.feedback(r.id, refreshed, sleepy)
                    scope.launch { runCatching { WatchSync(this@MainActivity).publishReports() } }
                    message = "평가 저장됨 · 기상 효과의 우열을 증명하는 결과는 아닙니다."
                } } }
                item { ActionChip("휴대전화에 기록 전송", { scope.launch { runCatching { WatchSync(this@MainActivity).publishReports() }.onSuccess { message = "전송 요청 완료" }.onFailure { message = "연결 확인 후 다시 시도하세요." } } }) }
                item { ActionChip("자체 기록 삭제", { scope.launch {
                    runCatching { WatchSync(this@MainActivity).deleteSyncedReports() }
                    store.clearReports(); dev.sleepalarm.diagnostics.DiagnosticArchive(this@MainActivity).use { it.erase() }; SensorDatabase(this@MainActivity).use { it.erase() }; message = "이 워치 기록 삭제됨"
                } }) }
            }
            if (message.isNotEmpty()) item { Text(message, color = MaterialTheme.colors.primary, textAlign = TextAlign.Center) }
            if (permissionMessage.isNotEmpty()) item { Text(permissionMessage, textAlign = TextAlign.Center) }
            if (alarmError.isNotEmpty()) item { Text("최근 알람 오류\n$alarmError", color = MaterialTheme.colors.error, textAlign = TextAlign.Center) }
        }
    }
}

private fun patternName(p: VibrationPattern) = when (p) { VibrationPattern.GENTLE -> "부드럽게"; VibrationPattern.PULSE -> "반복 박동"; VibrationPattern.URGENT -> "길게 반복" }

@Composable
private fun ActionChip(text: String, action: () -> Unit) = Chip(onClick = action, label = { Text(text) }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors())

@Composable
private fun Adjust(label: String, value: String, decrease: () -> Unit, increase: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = decrease, modifier = Modifier.size(38.dp)) { Text("−") }
        Text("$label $value", modifier = Modifier.width(80.dp), textAlign = TextAlign.Center)
        Button(onClick = increase, modifier = Modifier.size(38.dp)) { Text("+") }
    }
}

private fun modeLabel(mode: AlgorithmMode) = when (mode) { AlgorithmMode.ADAPTIVE -> "적응형"; AlgorithmMode.LEGACY -> "기존 0.1.1"; AlgorithmMode.ALTERNATE -> "매일 교대 시험"; AlgorithmMode.RANDOM -> "무작위 시험" }

@Composable
private fun WatchReport(r: NightReport, save: (Int, Int) -> Unit) {
    val diagnosticContext = androidx.compose.ui.platform.LocalContext.current
    var diagnosticSummary by remember(r.id) { mutableStateOf("상세 센서 진단은 진단 버튼에서 확인하세요.") }
    LaunchedEffect(r.id, r.motionBins, r.details.observationEnd) {
        diagnosticSummary = withContext(Dispatchers.IO) {
            runCatching { dev.sleepalarm.diagnostics.DiagnosticArchive(diagnosticContext).use { dev.sleepalarm.diagnostics.DiagnosticArchive.summary(it.get(r.id)) } }
                .getOrDefault("진단 저장소를 읽지 못했습니다.")
        }
    }
    var refreshed by remember(r.id) { mutableIntStateOf(r.details.refreshed.takeIf { it > 0 } ?: 3) }
    var sleepy by remember(r.id) { mutableIntStateOf(r.details.sleepiness.takeIf { it > 0 } ?: 3) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(diagnosticSummary)
        Text("신뢰도 0은 움직임 관문에서 평가가 보류된 경우도 있습니다. 실제 심박 수신·품질은 센서 진단에서 확인하세요.")
        Text("${time(r.deadline)} · ${r.details.algorithm}\n첫 알람 ${if (r.firstRingAt == 0L) "없음" else time(r.firstRingAt)}", textAlign = TextAlign.Center)
        Text("기존 제안 ${if (r.details.legacySuggestedAt == 0L) "관측 내 없음" else time(r.details.legacySuggestedAt)}\n적응형 제안 ${if (r.details.adaptiveSuggestedAt == 0L) "관측 내 없음" else time(r.details.adaptiveSuggestedAt)}\n관측 종료 이후는 비교 불가", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
        Text("움직임 신뢰 ${"%.2f".format(r.details.motionQuality)} · 심박 ${"%.2f".format(r.details.heartQuality)}\n${r.decision}", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
        if (r.firstRingAt > 0) {
            Adjust("개운함", "$refreshed/5", { refreshed = (refreshed - 1).coerceAtLeast(1) }, { refreshed = (refreshed + 1).coerceAtMost(5) })
            Adjust("졸림", "$sleepy/5", { sleepy = (sleepy - 1).coerceAtLeast(1) }, { sleepy = (sleepy + 1).coerceAtMost(5) })
            ActionChip("기상 평가 저장", { save(refreshed, sleepy) })
        }
    }
}
