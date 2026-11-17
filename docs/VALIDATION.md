# 실행한 검증과 남은 시험

검증 버전: **0.1.1 / versionCode 2**. 검증일: **2026-11-17 UTC**. Linux Cloud 개발 환경에서 소스를 생성하고 직접 빌드했습니다. Galaxy Watch7·휴대전화·Wear OS 에뮬레이터에서 앱을 실행한 결과는 포함하지 않습니다.

## 실행 결과

| 항목 | 결과 | 범위 |
|---|---|---|
| `:phone:assembleDebug` | 성공 | 휴대전화 debug APK 패키징 |
| `:wear:assembleDebug` | 성공 | 워치 debug APK 패키징 |
| `:core:model:test` | 9/9 통과 | 상태 전이, 최종 시각 유지, 다시 알림, 해제, 오래된 이벤트, DST, 입력 범위 |
| `:core:analysis:test` | 166/166 통과 | 기존 9개 + 연속 확인 4개 + 51개 범위 × 3개 경계 시험(153개). 각 범위의 실제 폴링 시험은 3개 bin 위상도 반복 |
| `:core:alarm:testDebugUnitTest` | 16/16 통과 | 기존 예약/복원 + 시험 설정 보존·실패 2개 + 서비스 시작/전경 승격 실패·대체 출력/정리 9개 |
| `:core:sensors:testDebugUnitTest` | 7/7 통과 | 선행 권한·OS 버전·사용자 미허용·설치자 제한·조회 불가 안내 정책 |
| `:phone:lintDebug`, `:wear:lintDebug` | 성공, Error 0 | 앱 정적 검사. Warning은 남음 |
| 모든 Android `core` 모듈 `lintDebug` | 성공, Error 0 | alarm/sensors/health/settings/sync. Warning은 남음 |
| 0.1.1 변경 후 최종 빌드·lint | 성공 | 두 APK 재생성, 알람/센서/설정 모듈과 두 앱 검사. 권한 문구 최종 조정 뒤 센서 테스트·워치 빌드·lint 재확인 |
| `./gradlew --version` 및 Wrapper 최종 앱 빌드·lint | 성공 | 고정 SHA-256의 Gradle 8.13을 받아 두 APK 재생성, Fragment 1.8.9 조합 검사 통과 |
| APK 서명 검사 | 양쪽 성공, 동일 인증서 | Android SDK `apksigner verify --print-certs` |
| 워치 APK metadata 검사 | 성공 | applicationId `dev.sleepalarm.app`, compile 36 / min 30 / target 35 |

자동 테스트는 합계 **198개, 실패 0개, 건너뜀 0개**입니다. 실제 알람 전달 지연·센서 정확도·기상 효과를 검증하는 시험은 아닙니다.

Robolectric 테스트는 Android API 34 프레임워크를 JVM에서 모의합니다. 예를 들어 LOCKED_BOOT_COMPLETED를 Receiver에 전달해 **저장된 계획을 재예약하는 코드 경로**를 확인합니다. 실제 암호화된 기기 부팅, 부팅 시간, OEM 배터리 정책을 검증하지 않습니다. 서비스 실패 시험은 Android 서비스 승격 경계에 예외를 주입하거나 Context에서 시작을 거부하는 JVM 모의입니다. 실제 OS가 서비스를 차단했을 때 시스템 알림·진동이 전달되는지는 검증하지 않습니다. 모의 AlarmManager는 센서 서비스가 없어도 최종 AlarmClockInfo가 유지되는지 확인합니다.

## 남은 정적 경고와 처리

- 의도적으로 고정한 AGP/Kotlin/Compose/기타 라이브러리보다 새 버전이 있다는 경고.
- targetSdk 35 경고. target 36 건강 권한 전환은 README에 별도 명시.
- Android KTX 표현, 리소스 디렉터리/단색 아이콘, 저장소 동기 commit, SDK 조건 간소화 등의 권고.
- 복원 Receiver는 `exported=false`이며 시스템 브로드캐스트 필터를 갖습니다. Lint의 protected broadcast action 검사 권고가 남습니다.
- ActivityResult 호환 검사에서 Google Play Services가 간접적으로 가져오는 Fragment 1.1.0을 발견했습니다. 양쪽 앱에서 Fragment 1.8.9를 명시해 해결했습니다. 이 오류에 대한 lint 억제는 최종 코드에 남기지 않았습니다.
- 이 환경에 NDK strip 도구가 없어 Compose의 graphics path `.so`가 strip되지 않았다는 빌드 안내가 있습니다. APK는 정상 패키징되었으며 release 크기 최적화는 하지 않았습니다.

원시 JUnit XML과 lint 결과는 [verification](verification/)에 보관합니다. 당시 빌드 환경의 절대 경로가 일부 포함되어 있습니다.

## 검증 환경

- Eclipse Temurin JDK 17.0.21, Gradle 8.13, AGP 8.13.2.
- Android SDK Platform 36, Build Tools 35.0.0.
- 환경에 초기 Android SDK/JDK compiler가 없어 작업 영역 안에 개발 도구를 설치해 사용했습니다. 이 도구들은 배포 소스 ZIP에 포함하지 않습니다.
- Cloud 환경의 읽기 전용 사용자 디렉터리 때문에 Gradle/Android 캐시는 작업 영역에 지정했습니다. Robolectric의 Android-all API 34/35 jar는 테스트 전용 외부 init script로 오프라인 경로를 지정했습니다. 일반 개발 머신에서는 Gradle과 Robolectric이 필요한 의존성을 자동으로 내려받습니다.
- 이 환경의 proxy/cache 경로를 프로젝트 Gradle 설정에 하드코딩하지 않았습니다. 소스 ZIP의 `local.properties`도 제외합니다.

## 실제 기기에서 아직 확인하지 않은 부분

다음 항목은 모두 **미검증**입니다.

- Watch7의 소리·진동 강도·패턴, 잠금 화면·전체 화면 알림, 해제·다시 알림 UX.
- Doze·절전·화면 꺼짐·기기 재부팅·앱 업데이트·일반 프로세스 종료에서의 실제 알람 실행과 시각 오차.
- `BODY_SENSORS_BACKGROUND`의 설치자 제한과 정상 사용자 허용 흐름.
- Watch7의 wake-up 가속도 센서 지원, Health Services 심박 품질 필드 제공, 실제 수집 주기·지연·누락·배터리 소비.
- 실제 삼성 헬스 → Health Connect 동기화와 데이터 출처·단계 값.
- 실제 휴대전화↔워치 Data Layer 연결, 명령 ACK, 오프라인 요약 기록 재전송.
- 권한의 실제 거부/설치자 제한 사유 표시, 공개 허용 목록 조회 가능 여부, 제조사 설정 화면 이동.
- FGS 차단 상황의 실제 대체 알림 소리·진동, 실패 서비스 정리 후 알림 유지와 해제 UX.
- 앱의 둥근 화면 잘림·접근성·글자 크기·다양한 화면 크기.
- 스마트 기상 규칙의 오탐·효과·사용자별 개인화 가능성.

[실기기 시험 계획](DEVICE_TEST_PLAN.md)에 항목별 절차와 기대 결과를 정리했습니다. 실기기 검증 전에는 알람 신뢰성이나 스마트 기상 효과가 입증되었다고 해석하지 마세요.
