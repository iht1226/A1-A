# Galaxy Watch7 스마트 수면 알람 0.2.0

Kotlin으로 만든 **휴대전화 Compose 앱 + 워치 Compose for Wear OS 앱**입니다. 워치가 다음 1회 기상 예약을 독립적으로 보관하고 실행합니다. 휴대전화는 설정 전송, 삼성 헬스 수면 기록 읽기, 자체 알람 이력 비교를 담당합니다.

**검증 상태:** 실제 Galaxy Watch7, 실제 Android 휴대전화, 실제 삼성 헬스 데이터로 검증하지 않았습니다. 빌드·자동 테스트 결과와 미검증 항목은 [검증 기록](docs/VALIDATION.md)에 구분합니다. 실사용 전에 반드시 낮에 기기 시험을 진행하세요.

새 적응형 설계·비교·개인화·한계: [0.2.0 알고리즘 문서](docs/ADAPTIVE_ALGORITHM.md). 이전 수정 이력: [0.1.1 기록](docs/CHANGELOG.md).

## 구현된 기능

- 워치 현지 시각의 다음 기상 시각 설정, 10~60분 스마트 범위, 일반 알람 모드.
- 최종 기상은 `AlarmManager.setAlarmClock()`으로 **센서·네트워크와 독립 예약**. 정확한 알람 권한이 없으면 예약 성공으로 표시하지 않음.
- 기본 최종 시각 45분 전부터 예열, 30분 전부터 판단. 10~60분 범위에 맞춰 예열을 자동 조정. 실제 측정 시각·수신 시각·품질·관측률을 사용.
- 움직임 강도·반복 빈도·지속·추세의 개인 기준선 기반 적응형 판단. 심박은 선택 보조이며 신뢰되지 않으면 움직임만 사용.
- 기존 0.1.1과 동일 관측의 인과 비교, 선택형 날짜별 교대/무작위 시험, 개운함·졸림 평가 및 제한적인 통계 개인화.
- 조기 해제 후 정시 백업 유지 옵션. 조기 다시 알림은 최종 시각을 넘기지 않음.
- 데이터 부족·지연·센서 권한 거부·능력 미지원·큰 움직임·판단 불확실성에는 조기 알람 보류.
- 진동 강도 1~255, 3개 패턴, 1~30분 다시 알림, 기본 시스템 알람 소리.
- 조기 알람이 울려도 최종 예약을 유지. 사용자가 **해제**하면 전체 예약 종료. 조기 알람에서 다시 알림을 눌러 최종 시각을 넘겨도 최종 알람은 원래 시각에 실행 대상으로 남음.
- 일반 프로세스 종료·최근 앱 제거와 별도로 PendingIntent 예약 유지. 재부팅·앱 업데이트 후 예약 복원. 잠금 해제 전 최소 예약 정보 접근.
- Health Connect에서 **삼성 헬스 출처**의 최근 7일 수면 기록을 읽고 REM·얕은·깊은 단계, 알려진 수면 합계 및 세션 길이 표시. 단계 미제공 상태를 구분.
- 첫 알람 시각·관측 수·판단 이유를 기록하고, 종료 시각이 가까운 삼성 헬스 기록과 비교.
- Wearable MessageClient로 설정 명령을 전달하고 워치가 실제 예약 후 응답. DataClient로 요약 기록을 재연결 후 동기화.

스마트 알고리즘은 **검증 전 규칙 기반 웰니스 가설**입니다. 움직임·심박만으로 REM/깊은 수면을 판별하지 않습니다. 새 근거 지수와 기존 0.1.1의 통과 등급은 서로 같은 확률 척도가 아닙니다. 초기 MVP는 반복 요일, 여러 알람, 머신러닝, 클라우드, 휴대전화 자체 알람은 포함하지 않습니다.

## 공식 SDK 호환성 및 선택

2026-11-17에 공식 문서와 Maven 아티팩트를 확인해 버전을 고정했습니다. 최신 버전 자동 추종 대신 재현 가능한 조합을 사용합니다.

| 구성 요소 | 고정 버전 / 설정 | 근거 |
|---|---|---|
| Android Gradle Plugin | 8.13.2 | API 36/36.1 지원, Gradle 8.13, JDK 17 기준 [공식 호환 표](https://developer.android.com/build/releases/agp-8-13-0-release-notes) |
| Gradle Wrapper | 8.13 | 공식 배포 SHA-256 고정 |
| Kotlin / Compose compiler plugin | 2.2.21 / 2.2.21 | Kotlin 2.x는 [동일 버전 Compose plugin](https://developer.android.com/develop/ui/compose/compiler)을 사용 |
| JVM bytecode / 권장 JDK | 17 / JDK 17 | Android Studio의 Gradle JDK를 17로 선택 |
| compileSdk / targetSdk / minSdk | 36 / 35 / 30 | Watch7의 Wear OS 5/API 34를 포함. 휴대전화는 Android 11 이상으로 제한 |
| Build Tools | 35.0.0 | AGP 기본 호환 버전 |
| Compose BOM | 2025.08.01 | [공식 BOM 매핑](https://developer.android.com/develop/ui/compose/bom/bom-mapping) |
| Wear Compose | 1.5.4 | [공식 안정 버전 기록](https://developer.android.com/jetpack/androidx/releases/wear-compose), BOM과 별도로 고정 |
| Activity Compose / Core KTX | 1.10.1 / 1.16.0 | API 36 빌드 조합 |
| Fragment (간접 의존성 보정) | 1.8.9 | Data Layer의 구형 간접 의존성을 [안정 버전](https://developer.android.com/jetpack/androidx/releases/fragment)으로 고정해 ActivityResult 호환 |
| Health Connect | 1.1.0 | [공식 안정 버전](https://developer.android.com/jetpack/androidx/releases/health-connect) |
| Health Services | 1.1.0 | [공식 안정 버전](https://developer.android.com/jetpack/androidx/releases/health), 이전 센서 접근 보안 수정 포함 |
| Wearable Data Layer | play-services-wearable 19.0.0 | [공식 릴리스 기록](https://developers.google.com/android/guides/releases), 양쪽 동일 applicationId·서명 필요 |
| Coroutines | 1.10.2 | Android, Guava future, Play Services Task 연동 |

Android Studio Otter(2025.2.1) 이상 중 **AGP 8.13을 지원하는 버전**을 사용하세요. IDE의 [AGP 지원 표](https://developer.android.com/studio/releases#android_gradle_plugin_and_android_studio_compatibility)를 확인하세요.

`targetSdk=35`는 개인용 설치 MVP의 명시적인 기준입니다. Wear OS 6/API 36 자체를 배제하는 설정은 아니지만 업그레이드 기기는 실기기 확인이 필요합니다. **targetSdk를 36 이상으로 올릴 때는** [Health Services 권한 전환](https://developer.android.com/health-and-fitness/health-services/permissions)에 따라 `READ_HEART_RATE`, `READ_HEALTH_DATA_IN_BACKGROUND` 등으로 선언·요청·체크를 함께 바꿔야 합니다. 숫자만 올리면 안 됩니다. Play Store 제출용 정책 검토·서명·Health 선언은 이 개인용 MVP 범위 밖입니다.

## 프로젝트 구조

```text
phone/          휴대전화 UI, Health Connect 권한, 기록 비교, Data Layer 수신
wear/           워치 UI, 권한 안내, 알람 화면, 원격 예약 수신
core/model/     순수 Kotlin 모델, 알람 상태 전이, 확장 인터페이스
core/analysis/  적응형/기존 엔진, 시간 집계, 인과 비교, 통계 개인화
core/settings/  설정·최소 예약 상태·요약 이력 저장과 직렬화
core/alarm/     AlarmManager, 복원 Receiver, 알람 Foreground Service
core/sensors/   PassiveMonitoringClient, SensorManager, 관측 DB, 스마트 범위 서비스
core/health/    휴대전화 전용 Health Connect 수면 기록 어댑터
core/sync/      Wearable MessageClient/DataClient 프로토콜
```

의존성 방향, 상태 전이, 알고리즘 기준과 후속 확장 지점은 [설계](docs/ARCHITECTURE.md)를 참고하세요. 삼성 헬스와 실시간 센서 분석 사이에 의존성이 없습니다.

## 빌드

Codex에서 APK 파일을 다운로드하기 어려우면 [APK 전용 ZIP과 GitHub Actions 다운로드 안내](docs/APK_DOWNLOAD.md)를 확인하세요. 워크플로는 [.github/workflows/build-apks.yml](.github/workflows/build-apks.yml)에 있습니다.

1. Android Studio에서 **이 README가 있는 디렉터리 전체**를 엽니다.
2. SDK Manager에서 Android SDK Platform 36, Build Tools 35.0.0, Platform Tools를 설치하고 라이선스에 동의합니다.
3. Gradle JDK를 17로 설정합니다. `local.properties`에 자신의 SDK 위치를 지정하거나 `ANDROID_HOME`을 설정합니다. 배포 ZIP에는 환경별 `local.properties`가 없습니다.
4. 인터넷 연결 후 Gradle Sync를 실행합니다.

```bash
# macOS / Linux
./gradlew :phone:assembleDebug :wear:assembleDebug
./gradlew :core:model:test :core:analysis:test :core:alarm:testDebugUnitTest :core:sensors:testDebugUnitTest
./gradlew :phone:lintDebug :wear:lintDebug

# Windows PowerShell
.\gradlew.bat :phone:assembleDebug :wear:assembleDebug
.\gradlew.bat :core:model:test :core:analysis:test :core:alarm:testDebugUnitTest :core:sensors:testDebugUnitTest
```

출력 APK:

```text
phone/build/outputs/apk/debug/phone-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

두 앱은 **같은 `dev.sleepalarm.app` applicationId**를 사용합니다. 서로 다른 기기에 설치해야 합니다. 같은 개발 머신의 기본 debug keystore로 두 APK를 함께 빌드하면 서명이 일치합니다. 다른 머신에서 따로 빌드하거나 한쪽을 release로 설치하면 Data Layer가 연결되지 않을 수 있습니다. 개인용 release 설치 시 양쪽을 본인 소유의 동일 keystore로 서명하세요. 키 파일은 저장소에 넣지 마세요.

## 설치와 최초 실행

### 휴대전화

- Galaxy Wearable로 Watch7을 페어링하고 Google Play Services를 사용할 수 있게 합니다.
- USB 디버깅을 켜고 Android Studio에서 `phone` 실행 구성을 휴대전화에 실행합니다.
- Android 14 이상은 시스템 Health Connect를 사용합니다. Android 11~13에서는 Google Play의 Health Connect 앱 설치·업데이트가 필요합니다. 제조사·프로필의 지원 여부는 런타임에서 확인합니다.
- 삼성 헬스 설정 → Health Connect에서 수면 데이터 **쓰기**를 허용합니다. 워치 수면 기록이 휴대전화 삼성 헬스에 동기화되도록 합니다.
- 이 앱 → 수면 기록 → 수면 읽기 연결에서 **읽기만** 허용합니다. ‘최근 7일 기록 읽기’를 누릅니다. 삼성 헬스 동기화는 즉시 완료된다고 가정하지 않습니다.

### 워치

워치 개발자 옵션에서 ADB 디버깅/무선 디버깅을 켜고 휴대전화와 같은 네트워크에서 연결합니다. OS 버전에 따라 메뉴명과 포트가 달라질 수 있습니다.

```bash
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:DEBUG_PORT
adb devices
adb -s PHONE_SERIAL install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s WATCH_IP:DEBUG_PORT install -r wear/build/outputs/apk/debug/wear-debug.apk
```

- 워치 앱 → 설정 → **알림**, **정확한 알람**을 먼저 허용합니다.
- 스마트 분석은 **움직임 분석 권한(ACTIVITY_RECOGNITION)**으로 심박 없이 실행할 수 있습니다. 선택 심박 보조를 원하면 **1. 심박 센서 허용** 후 **2. 센서 항상 허용**을 별도로 진행합니다. 두 권한을 한 번에 요청하지 않습니다. ‘항상 허용’은 시스템 설정에서 선택해야 할 수 있습니다.
- `BODY_SENSORS_BACKGROUND`는 제한된 권한입니다. 설치 방식·OS 정책으로 허용이 불가능할 수 있습니다. 앱 시스템 설정을 확인하고, 심박이 허용되지 않아도 움직임 분석 권한이 있으면 움직임만 사용합니다. 두 경로 모두 허용되지 않으면 일반 알람을 사용하세요. 화면은 사용자 허용 미완료, 확인된 설치자 제한, 제한 여부 확인 불가를 구분합니다. 시스템이 공개하지 않은 원인은 추측해 단정하지 않습니다. 권한 우회 코드는 없습니다.
- OS가 요구하면 전체 화면 알람 접근을 별도로 허용합니다. 전체 화면 접근이 거부되어도 소리·진동·알림 경로가 남습니다. 표시 방식은 실제 기기 확인이 필요합니다.
- 먼저 낮에 **2분 뒤 일반 알람 시험**으로 소리·진동·해제·다시 알림을 확인합니다. 시험 버튼은 기존 활성 예약을 교체하지만 저장된 사용자 설정은 변경하지 않습니다. 시험 계획에만 스마트 기능을 끕니다.
- 워치에서 최종 시각과 설정을 정한 후 **다음 기상 예약**을 누릅니다. 설정을 바꾸기만 하면 기존 예약은 바뀌지 않으며 다시 예약해야 합니다.
- 휴대전화에서 예약할 때는 워치가 연결되어 있어야 합니다. 워치의 ‘독립 알람 예약 완료’ 응답과 실제 워치 예약 시각을 확인하세요. 연결이 끊긴 후에는 워치 예약이 기준입니다.

## 알람 실행의 보장 범위

최종 시각 일반 알람은 스마트 판단과 무관하게 예약합니다. 센서 수집 실패, Health Connect 거부, 휴대전화 단절은 최종 예약을 취소하지 않습니다. 알람 예약에는 WorkManager, 네트워크, Compose 화면, 지속적인 센서 서비스를 사용하지 않습니다.

하지만 Android 앱은 **전원이 꺼져 있거나, 배터리가 방전되거나, 사용자가 앱을 강제 중지하거나, 정확한 알람 권한을 철회한 상황의 실행을 보장할 수 없습니다.** ‘최근 앱에서 제거’와 설정의 ‘강제 중지’는 다릅니다. 강제 중지는 OS가 알람을 취소하고 수신을 막을 수 있으며, 앱을 다시 열고 예약 상태를 복원해야 합니다. 재부팅 중에 지나간 시각은 부팅 후 가능한 시점에 복구 알람을 예약합니다. 원래 시각을 소급해 지킬 수는 없습니다.

`setAlarmClock()`은 공식적으로 Doze에서도 실행 대상이지만, 삼성 절전 설정·DND·극장 모드·알람 음량·진동 지원·전체 화면 정책은 실기기 검증이 필요합니다. 앱은 음량이나 방해 금지를 강제로 바꾸지 않습니다. [공식 알람 가이드](https://developer.android.com/develop/background-work/services/alarms), [Doze 제약](https://developer.android.com/training/monitoring-device-state/doze-standby)을 참고하세요.

## 데이터와 개인정보

- 실시간 심박·가속도 원본은 워치 앱 비공개 SQLite에만 보관합니다. 48시간이 지난 자료는 다음 수집 때 정리합니다. 앱이 실행되지 않을 때 정확히 48시간 후 삭제되는 스케줄은 없습니다.
- 요약 이력은 양쪽 최대 60회, 화면에는 최근 일부를 표시합니다. Data Layer에는 원본 센서 스트림 대신 요약만 보냅니다.
- Health Connect 기록은 읽기 시 화면 메모리에 보관하며 삼성 헬스 원본을 수정하지 않습니다. 앱 내 수면 읽기 권한 해제와 기록 삭제를 제공합니다.
- 휴대전화의 표시 기록 삭제는 워치 원본을 자동으로 삭제하지 않습니다. 양쪽 앱에서 각각 삭제하세요. 연결 중 동기화 항목 삭제가 실패하면 재동기화될 수 있습니다. 완전 초기화는 양쪽 앱 데이터를 지우세요.
- Health Connect의 완전한 수면 기록에서 유도한 참고 요약을 워치에 전송할 수 있으며 심박 원본 읽기 권한을 새로 요청하지 않습니다. 기상 평가는 양쪽에 저장하고 연결 시 워치로 재전송할 수 있습니다.
- 기기 재부팅 복원을 위한 최소 예약 상태만 Device Protected Storage에 저장합니다. 상세 건강 자료는 Credential Protected Storage에 둡니다. 백업은 비활성화합니다.
- 자체 서버·광고·분석 SDK는 없습니다. Data Layer 통신은 Google Play Services에 의존합니다.

## 검증과 다음 확장

[실기기 시험 절차](docs/DEVICE_TEST_PLAN.md), [실행 결과](docs/VALIDATION.md), [설계](docs/ARCHITECTURE.md), [공식 출처와 제약](docs/SDK_NOTES.md)를 함께 확인하세요.

후속 기능은 실측 자료로 `AdaptiveParameters` 검증, 개인화의 편향·효과 평가, 반복 알람 스케줄 정책 추가, Room/DataStore 마이그레이션, 더 풍부한 관측 기록, targetSdk 36 권한 전환 등으로 확장할 수 있습니다. 개인화 결과가 최종 알람의 예약·실행 조건이 되지 않도록 유지하세요.
