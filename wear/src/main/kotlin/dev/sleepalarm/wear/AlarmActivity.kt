package dev.sleepalarm.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import dev.sleepalarm.alarm.AlarmController
import dev.sleepalarm.model.RingKind
import dev.sleepalarm.settings.SettingsStore
import kotlinx.coroutines.delay

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        setContent {
            MaterialTheme {
                val store = remember { SettingsStore(this) }
                var plan by remember { mutableStateOf(store.plan()) }
                var error by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    while (true) {
                        plan = store.plan()
                        if (plan?.ringing != true) { finish(); break }
                        delay(500)
                    }
                }
                ScalingLazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    item { Text("기상 시간", style = MaterialTheme.typography.title1) }
                    item { Text(if (plan?.ringKind == RingKind.SMART) "관측 변화에 따른 스마트 알람" else "일반 알람", textAlign = TextAlign.Center) }
                    item { Chip(onClick = { plan?.let { AlarmController(this@AlarmActivity).dismiss(it.ringNonce) }; finish() }, label = { Text("알람 해제") }, modifier = Modifier.fillMaxWidth()) }
                    item { Chip(onClick = {
                        runCatching { plan?.let { AlarmController(this@AlarmActivity).snooze(it.ringNonce) } }
                            .onSuccess { finish() }.onFailure { error = it.message ?: "다시 알림 실패" }
                    }, label = { Text("${plan?.settings?.snoozeMinutes ?: 5}분 뒤 다시 알림") }, modifier = Modifier.fillMaxWidth(), colors = ChipDefaults.secondaryChipColors()) }
                    if (error.isNotEmpty()) item { Text(error, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}
