package dev.sleepalarm.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("수면 데이터 사용 안내", style = MaterialTheme.typography.headlineMedium)
                Text("개인용 수면 알람 MVP · 2026-11-17")
                Text("Health Connect의 수면 읽기 권한으로 삼성 헬스가 동기화한 최근 7일의 수면 세션과 단계를 휴대전화 화면에 표시하고 워치 관측 이력과 비교합니다. 삼성 헬스나 Health Connect에 기록을 쓰지 않습니다. 이 데이터는 실시간 조기 알람의 입력으로 사용하지 않습니다.")
                Text("워치에서는 사용자가 허용한 심박수와 가속도 변화를 기상 시점 추정에 사용합니다. 센서 원본은 워치 앱의 비공개 저장소에 최대 48시간 보관하며 다음 수집 때 정리합니다. 관측 수와 알람 이력은 최대 60회 보관합니다. 워치에서 기록을 삭제할 수 있습니다.")
                Text("휴대전화와 워치 사이에 알람 설정과 요약 이력을 Wearable Data Layer로 동기화합니다. 앱 자체 서버, 광고, 분석 SDK는 없습니다. 백업은 비활성화되어 있습니다. 재부팅 직후 알람을 복원하기 위한 최소 예약 정보는 워치 잠금 해제 전에도 접근 가능한 앱 전용 저장소에 보관합니다. 심박·움직임 원본은 여기에 저장하지 않습니다.")
                Text("수면 기록 화면에서 Health Connect 권한을 해제하거나 시스템 설정에서 철회할 수 있습니다. 앱 기록 지우기는 삼성 헬스 원본을 삭제하지 않습니다. 양쪽 앱의 기록을 모두 삭제하려면 휴대전화와 워치에서 각각 삭제하거나 앱 데이터를 지우세요. Health Connect 기록은 현재 화면 메모리에만 유지됩니다.")
                Text("수면 단계 판별이나 의료 진단을 제공하지 않습니다. 실제 Galaxy Watch7에서 알람과 배터리 영향을 확인해야 합니다.")
                Button(onClick = { finish() }) { Text("닫기") }
            }
        } } }
    }
}
