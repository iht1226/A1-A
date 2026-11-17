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
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var permissionMessage by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colors = Colors(primary = Color(0xFF9CE5D5), secondary = Color(0xFFBBC4FF))) {
                WatchHome(
                    permission = { which ->
                        when (which) {
                            "notification" -> if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                            "exact" -> if (Build.VERSION.SDK_INT >= 31) open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) open(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                            else -> open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        }
                    },
                )
            }
        }
    }
    override fun onResume() { super.onResume(); permissionRevision++; runCatching { AlarmController(this).restore() } }
    private fun open(action: String) {
        runCatching { startActivity(Intent(action, Uri.parse("package:$packageName"))) }
            .onFailure { permissionMessage = "시스템 설정 화면을 열 수 없습니다 (${it.javaClass.simpleName}). 워치 설정 → 앱 → 수면 알람 → 권한을 직접 확인하세요." }
    }

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
                sensors = PassiveHeartMonitor.permitted(this@MainActivity)
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
                    check(notify) { "알림 권한을 먼저 허용하세요." }
                    val p = alarm.arm(settings); plan = p; message = "예약됨: ${time(p.deadline)}"
                } }, label = { Text("다음 기상 예약") }, modifier = Modifier.fillMaxWidth()) }
                if (plan?.enabled == true) {
                    item { Text("최종 기상 ${time(plan!!.deadline)}\n${if (plan!!.ringing) "알람 울리는 중" else if (plan!!.snoozeAt > 0) "다시 알림 ${time(plan!!.snoozeAt)}" else "워치에 예약됨"}", textAlign = TextAlign.Center) }
                    item { Chip(onClick = { alarm.dismiss(); plan = store.plan(); message = "예약 해제됨" }, label = { Text("예약 해제") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                }
                item { Text(status, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                if (!sensors && settings.smartEnabled) item { Text(sensorAccess.explanation, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            }
            if (tab == 1) {
                item { ToggleChip(checked = settings.smartEnabled, onCheckedChange = { change(settings.copy(smartEnabled = it)) },
                    label = { Text("스마트 기상") }, toggleControl = { Switch(checked = settings.smartEnabled) }, modifier = Modifier.fillMaxWidth()) }
                item { Adjust("범위", "${settings.windowMinutes}분", { change(settings.copy(windowMinutes = (settings.windowMinutes - 5).coerceAtLeast(10))) }, { change(settings.copy(windowMinutes = (settings.windowMinutes + 5).coerceAtMost(60))) }) }
                item { Adjust("진동", "${settings.amplitude}", { change(settings.copy(amplitude = (settings.amplitude - 32).coerceAtLeast(1))) }, { change(settings.copy(amplitude = (settings.amplitude + 32).coerceAtMost(255))) }) }
                item { Chip(onClick = { change(settings.copy(pattern = VibrationPattern.entries[(settings.pattern.ordinal + 1) % 3])) }, label = { Text("패턴: ${patternName(settings.pattern)}") }, modifier = Modifier.fillMaxWidth()) }
                item { Adjust("다시 알림", "${settings.snoozeMinutes}분", { change(settings.copy(snoozeMinutes = (settings.snoozeMinutes - 1).coerceAtLeast(1))) }, { change(settings.copy(snoozeMinutes = (settings.snoozeMinutes + 1).coerceAtMost(30))) }) }
                item { Chip(onClick = { execute { AlarmNotifications.vibrate(this@MainActivity, settings, false) } }, label = { Text("진동 미리 보기") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("변경한 설정은 다음 예약에 적용됩니다. 진동 강도는 기기 지원 범위에 따릅니다.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { Text("권한 · 일반 알람 ${if (exact && notify) "준비됨" else "확인 필요"}\n스마트 센서 ${if (sensors) "허용됨" else "제한됨"}", textAlign = TextAlign.Center) }
                item { Text(sensorAccess.explanation, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                item { ActionChip("알림 권한", { permission("notification") }) }
                item { ActionChip("정확한 알람 권한", { permission("exact") }) }
                item { ActionChip("1. 심박 센서 허용", { permission("body") }) }
                item { ActionChip("2. 센서 항상 허용", { permission("background") }) }
                item { ActionChip("전체 화면 알람 허용", { permission("fullscreen") }) }
                item { ActionChip("앱 시스템 설정", { permission("settings") }) }
                item { ActionChip("2분 뒤 일반 알람 시험", { execute {
                    check(exact && notify) { "정확한 알람·알림 권한을 허용하세요." }
                    val p = alarm.armTest(settings)
                    plan = p; message = "시험 예약: ${time(p.deadline)}"
                } }) }
            }
            if (tab == 2) {
                item { Text("자체 기록은 관측·알람 이력입니다. 총 수면 시간과 수면 단계를 측정하지 않습니다.", textAlign = TextAlign.Center) }
                if (reports.isEmpty()) item { Text("아직 기록이 없습니다.") }
                reports.take(7).forEach { r -> item { Text(
                    "${time(r.deadline)}\n첫 알람 ${if (r.firstRingAt == 0L) "없음" else time(r.firstRingAt)}\n움직임 ${r.motionBins}개 · 심박 ${r.heartSamples}개\n${r.decision}",
                    style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
                ) } }
                item { ActionChip("휴대전화에 기록 전송", { scope.launch { runCatching { WatchSync(this@MainActivity).publishReports() }.onSuccess { message = "전송 요청 완료" }.onFailure { message = "연결 확인 후 다시 시도하세요." } } }) }
                item { ActionChip("자체 기록 삭제", { scope.launch {
                    runCatching { WatchSync(this@MainActivity).deleteSyncedReports() }
                    store.clearReports(); SensorDatabase(this@MainActivity).use { it.erase() }; message = "이 워치 기록 삭제됨"
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
