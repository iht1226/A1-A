package dev.sleepalarm.phone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import dev.sleepalarm.health.SleepHistoryRepository
import dev.sleepalarm.model.*
import dev.sleepalarm.settings.SettingsStore
import dev.sleepalarm.sync.WatchSync
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val mint = Color(0xFF9CE5D5)
private fun dateTime(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
private fun duration(minutes: Long) = "${minutes / 60}시간 ${minutes % 60}분"
private fun stageColor(stage: HistoricalStage) = when (stage) {
    HistoricalStage.REM -> Color(0xFFBBC4FF)
    HistoricalStage.LIGHT -> Color(0xFF9CE5D5)
    HistoricalStage.DEEP -> Color(0xFF5B8DDB)
    HistoricalStage.AWAKE, HistoricalStage.OUT_OF_BED -> Color(0xFFEDB98F)
    else -> Color(0xFF4C5665)
}

class MainActivity : ComponentActivity() {
    private var permissionVersion by mutableIntStateOf(0)
    private val healthPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { permissionVersion++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = mint, secondary = Color(0xFFBBC4FF), background = Color(0xFF0E141C), surface = Color(0xFF19222E))) {
                Surface(Modifier.fillMaxSize()) { PhoneHome() }
            }
        }
    }
    override fun onResume() { super.onResume(); permissionVersion++ }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun PhoneHome() {
        val store = remember { SettingsStore(this) }
        val health = remember { SleepHistoryRepository(this) }
        val sync = remember { WatchSync(this) }
        val scope = rememberCoroutineScope()
        var settings by remember { mutableStateOf(store.settings()) }
        var hour by remember { mutableStateOf("%02d".format(settings.hour)) }
        var minute by remember { mutableStateOf("%02d".format(settings.minute)) }
        var tab by remember { mutableIntStateOf(0) }
        var status by remember { mutableStateOf("워치가 실제 예약을 담당합니다.") }
        var ack by remember { mutableStateOf(store.ack()) }
        var reports by remember { mutableStateOf(store.reports()) }
        var sleeps by remember { mutableStateOf(emptyList<SleepHistory>()) }
        var healthStatus by remember { mutableStateOf("수면 읽기 권한과 삼성 헬스 동기화를 확인하세요.") }
        var canRead by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val refreshHealth: () -> Unit = {
            scope.launch {
                busy = true
                try {
                    canRead = health.granted()
                    if (!canRead) { sleeps = emptyList(); healthStatus = "Health Connect 수면 읽기 권한이 필요합니다." }
                    else {
                        sleeps = withContext(Dispatchers.IO) { health.readSamsungSleep(Instant.now().minus(Duration.ofDays(7)), Instant.now()) }
                        val readAt = System.currentTimeMillis()
                        store.reports().forEach { store.putReport(SleepSummaries.attach(it, sleeps, readAt)) }
                        reports = store.reports()
                        store.sleepContext(SleepSummaries.context(sleeps, readAt))
                        healthStatus = if (sleeps.isEmpty()) "지난 7일 삼성 헬스 수면 기록이 없습니다. 삼성 헬스의 Health Connect 쓰기 권한과 동기화를 확인하세요." else "삼성 헬스 · 최근 7일 ${sleeps.size}개 세션 (기상 후 동기화 기록)"
                    }
                } catch (e: Exception) { sleeps = emptyList(); healthStatus = "기록 읽기 실패: ${e.message?.take(120)}" }
                finally { busy = false }
            }
        }
        LaunchedEffect(permissionVersion) {
            canRead = runCatching { health.granted() }.getOrDefault(false)
            if (!canRead) {
                sleeps = emptyList()
                if (store.sleepContext().nights > 0) {
                    store.sleepContext(SleepContext(recordedAt = System.currentTimeMillis()))
                    runCatching { withTimeout(12_000) { sync.sendContext(store.sleepContext()) } }
                }
            }
        }
        LaunchedEffect(Unit) {
            runCatching { withTimeout(12_000) { sync.refresh() } }
            while (true) { reports = store.reports(); ack = store.ack(); delay(1_000) }
        }
        fun save(s: AlarmSettings) { settings = s; store.saveSettings(s) }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                Text("SLEEP / WAKE", style = MaterialTheme.typography.labelMedium, color = mint)
                Text("수면 알람", style = MaterialTheme.typography.headlineLarge)
                Text("Galaxy Watch7 · 개인용 MVP", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("기상 설정", "수면 기록", "연결 안내").forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tab == 0) {
                    item { Panel {
                        Text("최종 기상 시각", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(hour, { hour = it.filter(Char::isDigit).take(2) }, label = { Text("시 · 0–23") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            Text(":", style = MaterialTheme.typography.headlineLarge)
                            OutlinedTextField(minute, { minute = it.filter(Char::isDigit).take(2) }, label = { Text("분 · 0–59") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                        Text("워치 현지 시각의 다음 1회 기상입니다. 반복 알람은 후속 확장 기능입니다.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("스마트 기상", modifier = Modifier.weight(1f)); Switch(settings.smartEnabled, { save(settings.copy(smartEnabled = it)) })
                        }
                        Text("스마트 범위 · ${settings.windowMinutes}분")
                        Slider(value = settings.windowMinutes.toFloat(), onValueChange = { save(settings.copy(windowMinutes = it.toInt().coerceIn(10, 60))) }, valueRange = 10f..60f, steps = 49, enabled = settings.smartEnabled)
                        Text("개인 기준선 대비 반복 움직임을 주로 보며 심박은 선택 보조 지표입니다. 기본은 45분 전 측정, 30분 전 판단입니다. 수면 단계를 분류하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                        Text("알고리즘과 개인 비교 시험", style = MaterialTheme.typography.titleMedium)
                        AlgorithmMode.entries.forEach { mode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(settings.algorithmMode == mode, { save(settings.copy(algorithmMode = mode)) })
                                Text(when (mode) { AlgorithmMode.ADAPTIVE -> "적응형"; AlgorithmMode.LEGACY -> "기존 0.1.1"; AlgorithmMode.ALTERNATE -> "매일 번갈아 사용"; AlgorithmMode.RANDOM -> "날짜별 무작위 배정" })
                            }
                        }
                        Text("교대·무작위는 선택한 경우에만 사용합니다. 날짜별 조건이 다르므로 결과를 일반화하지 마세요.", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("조기 해제 후에도 정시 백업 유지", modifier = Modifier.weight(1f))
                            Switch(settings.keepFinalAfterSmartDismiss, { save(settings.copy(keepFinalAfterSmartDismiss = it)) })
                        }
                        Text("보수 기준 ${"%.2f".format(settings.adaptiveThreshold)} · 높을수록 조기 판단을 줄입니다")
                        Slider(settings.adaptiveThreshold.toFloat(), { save(settings.copy(adaptiveThreshold = it.toDouble().coerceIn(0.65, 0.90))) }, valueRange = 0.65f..0.90f)

                    } }
                    item { Panel {
                        Text("진동과 다시 알림", style = MaterialTheme.typography.titleMedium)
                        Text("진동 강도 · ${settings.amplitude}/255")
                        Slider(settings.amplitude.toFloat(), { save(settings.copy(amplitude = it.toInt().coerceIn(1, 255))) }, valueRange = 1f..255f)
                        Text("기기가 강도 조절을 지원하지 않으면 기본 강도로 진동합니다.", style = MaterialTheme.typography.bodySmall)
                        VibrationPattern.entries.forEach { pattern ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(settings.pattern == pattern, { save(settings.copy(pattern = pattern)) })
                                Text(when (pattern) { VibrationPattern.GENTLE -> "부드럽게"; VibrationPattern.PULSE -> "반복 박동"; VibrationPattern.URGENT -> "길게 반복" })
                            }
                        }
                        Text("다시 알림 · ${settings.snoozeMinutes}분")
                        Slider(settings.snoozeMinutes.toFloat(), { save(settings.copy(snoozeMinutes = it.toInt().coerceIn(1, 30))) }, valueRange = 1f..30f, steps = 28)
                    } }
                    item { Button(onClick = {
                        val h = hour.toIntOrNull(); val m = minute.toIntOrNull()
                        if (h == null || m == null || h !in 0..23 || m !in 0..59) status = "시(0–23)와 분(0–59)을 확인하세요."
                        else {
                            val s = settings.copy(hour = h, minute = m); save(s)
                            scope.launch { busy = true
                                status = runCatching { withTimeout(12_000) { sync.armWatch(s) } }.getOrElse { it.message ?: "전송 실패" }
                                busy = false
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("워치에 전송하고 예약") } }
                    item { Text(status) }
                    item { Panel { Text("워치 응답", color = mint); Text(ack); Text("전송 성공만으로 예약 완료를 판단하지 마세요. 워치 화면의 최종 기상 시각을 확인하세요.", style = MaterialTheme.typography.bodySmall) } }
                }
                if (tab == 1) {
                    item { Panel {
                        Text("삼성 헬스 수면 기록", style = MaterialTheme.typography.titleLarge)
                        Text(healthStatus)
                        Button(onClick = {
                            when (health.sdkStatus()) {
                                HealthConnectClient.SDK_AVAILABLE -> healthPermission.launch(health.readPermissions)
                                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }.onFailure { healthStatus = "Health Connect 설치 또는 업데이트가 필요합니다." }
                                else -> healthStatus = "이 휴대전화는 Health Connect를 지원하지 않습니다."
                            }
                        }) { Text(if (canRead) "수면 권한 확인" else "수면 읽기 연결") }
                        OutlinedButton(onClick = refreshHealth, enabled = !busy) { Text("최근 7일 기록 읽기") }
                        OutlinedButton(onClick = { scope.launch {
                            status = runCatching { withTimeout(12_000) { sync.sendContext(store.sleepContext()) }; "개인화용 수면 요약 전송 요청됨" }.getOrElse { it.message ?: "연결 실패" }
                        } }) { Text("수면 요약을 워치 개인화에 사용") }
                        Text("완전한 단계 기록이 있는 최근 수면의 요약만 전송합니다. 심박 원본을 Health Connect에서 읽지 않습니다.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { scope.launch { status = runCatching { withTimeout(12_000) { sync.refresh() }; "워치 기록 새로고침 요청됨" }.getOrElse { it.message ?: "연결 실패" } } }) { Text("워치 관측 기록 받기") }
                        Text("수면 기록은 휴대전화에서만 읽습니다. 삼성 헬스의 실시간 수면 단계 API를 사용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    } }
                    items(sleeps, key = { "sleep-${it.id}" }) { sleep ->
                        val candidate = reports.filter { it.firstRingAt > 0 && abs(it.firstRingAt - sleep.end.toEpochMilli()) < 6 * 3_600_000 }
                            .minByOrNull { abs(it.firstRingAt - sleep.end.toEpochMilli()) }
                        SleepCard(sleep, candidate)
                    }
                    item { Panel {
                        Text("개인 비교 시험 요약", style = MaterialTheme.typography.titleLarge)
                        Text("선택한 교대·무작위 시험만 집계합니다. 무작위 배정은 일별 조건 차이를 없애지 않습니다.", style = MaterialTheme.typography.bodySmall)
                        AlgorithmId.entries.forEach { algorithm ->
                            val nights = reports.filter { it.details.smartScheduled && !it.details.testAlarm && it.firstRingAt > 0 &&
                                it.details.mode in listOf(AlgorithmMode.ALTERNATE, AlgorithmMode.RANDOM) && it.details.algorithm == algorithm }
                            val rated = nights.filter { it.details.refreshed in 1..5 && it.details.sleepiness in 1..5 }
                            Text("$algorithm · ${nights.size}회 · 평가 ${rated.size}회 · 조기 ${nights.count { it.firstRingKind == "SMART" }}회")
                            if (rated.isNotEmpty()) Text("개운함 평균 ${"%.1f".format(rated.map { it.details.refreshed }.average())} / 졸림 평균 ${"%.1f".format(rated.map { it.details.sleepiness }.average())}")
                            val knownSleep = nights.filter { it.details.healthSleepMinutes >= 0 }
                            if (knownSleep.isNotEmpty()) Text("삼성 참고 수면 평균 ${knownSleep.map { it.details.healthSleepMinutes }.average().toInt()}분 (${knownSleep.size}회)")
                        }
                        Text("우열 검정이나 수면 조건 보정 결과가 아닙니다. 실제 출력·해제·배터리·수면 기록의 누락은 각 이력에서 확인하세요.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { Text("워치 자체 관측·알람 이력", style = MaterialTheme.typography.titleLarge) }
                    if (reports.isEmpty()) item { Text("워치에서 알람을 예약하고 기록을 전송하세요.") }
                    items(reports.take(30), key = { "report-${it.id}" }) { r -> ReportCard(r) { refreshed, sleepy ->
                        val at = System.currentTimeMillis()
                        store.feedback(r.id, refreshed, sleepy, at)
                        reports = store.reports()
                        scope.launch { status = runCatching {
                            withTimeout(12_000) { sync.sendFeedback(r.id, refreshed, sleepy, at) }; "평가 저장·전송 요청됨"
                        }.getOrElse { it.message ?: "평가 로컬 저장됨 · 연결 후 새로고침으로 재전송" } }
                    } }
                    item { OutlinedButton(onClick = { scope.launch {
                        runCatching { withTimeout(12_000) { sync.deleteSyncedReports() } }
                        store.clearReports(); reports = emptyList(); sleeps = emptyList()
                        healthStatus = "이 앱의 표시 기록을 지웠습니다. 워치 원본은 워치 기록 화면에서 별도로 삭제하세요."
                    } }) { Text("앱 기록 지우기") } }
                    item { OutlinedButton(onClick = { scope.launch { runCatching { health.revoke() }; store.sleepContext(SleepContext(recordedAt = System.currentTimeMillis())); runCatching { withTimeout(12_000) { sync.sendContext(store.sleepContext()) } }; canRead = false; sleeps = emptyList(); healthStatus = "Health Connect 권한 해제됨" } }) { Text("수면 읽기 권한 해제") } }
                    item { Text(status, style = MaterialTheme.typography.bodySmall) }
                }
                if (tab == 2) {
                    item { Panel {
                        Text("처음 사용하는 순서", style = MaterialTheme.typography.titleLarge)
                        Text("1. 휴대전화와 워치에 같은 서명으로 빌드한 앱을 설치합니다.\n\n2. 워치 앱의 설정에서 알림과 정확한 알람 권한을 허용합니다. 움직임 분석 권한을 허용하면 심박 없이도 분석합니다. 심박 보조를 원하면 심박 센서 허용 후 ‘항상 허용’을 별도로 설정합니다.\n\n3. 삼성 헬스에서 Health Connect 수면 쓰기를 켜고, 이 앱에서 수면 읽기를 허용합니다.\n\n4. 낮에 워치의 ‘2분 뒤 일반 알람 시험’으로 진동, 소리, 해제, 다시 알림을 확인합니다.\n\n5. 워치에서 직접 예약하거나 휴대전화에서 전송한 뒤 워치의 예약 시각을 확인합니다.")
                    } }
                    item { Panel {
                        Text("MVP의 범위", style = MaterialTheme.typography.titleLarge)
                        Text("센서 데이터는 지연되거나 누락될 수 있습니다. 심박·움직임만으로 REM·깊은 수면을 판별하지 않으며, 규칙 기반 스마트 기상의 효과는 아직 검증되지 않았습니다.\n\n휴대전화 연결과 센서 수집은 워치 최종 알람의 실행 조건이 아닙니다. 다만 전원 꺼짐, 배터리 방전, 강제 중지, 권한 철회, 시스템 제한 상황에서 실행을 보장할 수 없습니다. 실제 Watch7에서 검증한 뒤 사용하세요.")
                        TextButton(onClick = { startActivity(Intent(this@MainActivity, PrivacyActivity::class.java)) }) { Text("데이터 사용 안내") }
                    } }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
private fun SleepCard(s: SleepHistory, report: NightReport?) {
    val sessionMinutes = Duration.between(s.start, s.end).toMinutes()
    fun minutes(stage: HistoricalStage) = s.stages.filter { it.stage == stage }
        .sumOf { Duration.between(it.start, it.end).toMillis() } / 60_000
    val asleep = s.stages.filter { it.stage in setOf(HistoricalStage.SLEEPING, HistoricalStage.LIGHT, HistoricalStage.DEEP, HistoricalStage.REM) }
        .sumOf { Duration.between(it.start, it.end).toMillis() } / 60_000
    Panel {
        Text("${dateTime(s.start.toEpochMilli())} → ${dateTime(s.end.toEpochMilli())}", style = MaterialTheme.typography.titleMedium)
        Text("세션 길이 ${duration(sessionMinutes)}")
        Text(if (s.stages.isEmpty()) "수면 시간·단계: 제공되지 않음" else "기록된 수면 합계 ${duration(asleep)} · 누락 구간 제외")
        val total = Duration.between(s.start, s.end).toMillis().toFloat().coerceAtLeast(1f)
        Canvas(Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF303A49))) {
            s.stages.forEach { stage ->
                val from = (Duration.between(s.start, stage.start).toMillis() / total).coerceIn(0f, 1f)
                val until = (Duration.between(s.start, stage.end).toMillis() / total).coerceIn(from, 1f)
                drawRect(stageColor(stage.stage), Offset(size.width * from, 0f), Size(size.width * (until - from), size.height))
            }
        }
        Text("REM ${minutes(HistoricalStage.REM)}분 · 얕은 ${minutes(HistoricalStage.LIGHT)}분 · 깊은 ${minutes(HistoricalStage.DEEP)}분")
        Text("출처: 삼성 헬스 → Health Connect", color = mint, style = MaterialTheme.typography.bodySmall)
        if (report != null) {
            val delta = Duration.between(s.end, Instant.ofEpochMilli(report.firstRingAt)).toMinutes()
            Text("비교 후보 · 삼성 기록 종료 대비 첫 알람 ${if (delta >= 0) "+" else ""}${delta}분")
            Text("워치: 움직임 ${report.motionBins}구간 / 심박 ${report.heartSamples}회\n${report.decision}", style = MaterialTheme.typography.bodySmall)
            Text("종료 시각이 가까운 기록을 연결한 참고 비교입니다. 단계 정확도 비교가 아닙니다.", style = MaterialTheme.typography.bodySmall)
        } else Text("비교할 워치 알람 기록이 없습니다.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReportCard(r: NightReport, save: (Int, Int) -> Unit) {
    var refreshed by remember(r.id) { mutableIntStateOf(r.details.refreshed.takeIf { it > 0 } ?: 3) }
    var sleepy by remember(r.id) { mutableIntStateOf(r.details.sleepiness.takeIf { it > 0 } ?: 3) }
    Panel {
        val d = r.details
        Text("최종 기상 ${dateTime(r.deadline)} · ${if (d.testAlarm) "시험 알람" else if (!d.smartScheduled) "일반/기존 기록" else d.algorithm.name}", style = MaterialTheme.typography.titleMedium)
        Text("예약 ${dateTime(r.armedAt)}\n첫 알람 요청 ${if (r.firstRingAt == 0L) "없음" else dateTime(r.firstRingAt)} · ${r.firstRingKind}\n출력 API 시작 ${if (d.outputStartedAt == 0L) "미확인" else dateTime(d.outputStartedAt)}\n첫 해제 ${if (d.firstDismissedAt == 0L) "없음" else dateTime(d.firstDismissedAt)}")
        Text("동일 관측 비교\n기존 제안 ${if (d.legacySuggestedAt == 0L) "관측 내 없음" else dateTime(d.legacySuggestedAt)}\n적응형 제안 ${if (d.adaptiveSuggestedAt == 0L) "관측 내 없음" else dateTime(d.adaptiveSuggestedAt)}\n관측 종료 ${if (d.observationEnd > 0) dateTime(d.observationEnd) else "없음"} 이후는 비교 불가")
        Text("기존 통과 등급 ${"%.2f".format(d.legacyConfidence)} / 적응 근거 지수 ${"%.2f".format(d.adaptiveConfidence)} · 서로 같은 확률 척도가 아닙니다.", style = MaterialTheme.typography.bodySmall)
        Text("기존 근거: ${d.legacyReason}\n적응형 근거: ${d.adaptiveReason}", style = MaterialTheme.typography.bodySmall)
        Text("움직임 신뢰 ${"%.2f".format(d.motionQuality)} / 심박 신뢰 ${"%.2f".format(d.heartQuality)} · 확률이 아닌 내부 품질 지표\n개인화 평가 ${d.personalizedNights}회 · 기준 조정 ${"%.3f".format(d.personalizationOffset)}")
        Text("삼성 수면 합계 ${if (d.healthSleepMinutes < 0) "누락/불완전/미연결" else duration(d.healthSleepMinutes)} · 참고 후보 기록")
        Text(if (d.batteryStart >= 0 && d.batteryEnd in 0..d.batteryStart) "센서 측정 구간 배터리 ${d.batteryStart} → ${d.batteryEnd}% (${d.batteryStart - d.batteryEnd}%p)" else "배터리 미완료/미지원/충전 상태")
        Text("움직임 ${r.motionBins}구간 · 심박 ${r.heartSamples}회\n${r.decision}")
        if (r.firstRingAt > 0) {
            Text("기상 후 평가 · 1 낮음 ~ 5 높음")
            Text("개운함 $refreshed/5")
            Slider(refreshed.toFloat(), { refreshed = it.toInt().coerceIn(1, 5) }, valueRange = 1f..5f, steps = 3)
            Text("졸림 $sleepy/5")
            Slider(sleepy.toFloat(), { sleepy = it.toInt().coerceIn(1, 5) }, valueRange = 1f..5f, steps = 3)
            Button(onClick = { save(refreshed, sleepy) }) { Text("평가 저장·워치 전송") }
        }
        Text("배정별 여러 날을 관찰하는 개인 비교입니다. 판단 차이나 일별 점수로 기상 효과의 우열을 증명하지 않습니다. 자체 관측은 총 수면 시간·수면 단계를 산출하지 않습니다.", style = MaterialTheme.typography.bodySmall)
    }
}
