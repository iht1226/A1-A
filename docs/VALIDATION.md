# 0.4.0 수집 신뢰성 검증

로컬 JDK 17 / SDK 36 / 기존 Wrapper로 **Kotlin/JVM·Robolectric 319개**(model 16, analysis 245, alarm 29, sensors 17, update 9, diagnostics 3)가 통과했습니다. 실패/오류/건너뜀 0개입니다. Python의 기존 인증서 pin 정책 3개도 통과했습니다. 합계 322개입니다. 기존 299개 회귀 시험을 유지합니다.

두 debug APK 빌드, phone/wear/core:sensors/core:diagnostics lint Error 0, actionlint 검사도 통과했습니다. 원시 XML과 lint 결과는 verification에 갱신했습니다. 이 Cloud 환경의 Robolectric 런타임은 기존 외부 offline init script를 사용하며 그 로컬 경로를 프로젝트나 CI에 넣지 않았습니다.

추가 시험은 5초 경계 시간 보존, 불규칙 간격과 긴 공백, 60초 넘는 배치 지연, 미래/중복/역순/정확도 오류, FIFO 정책, 정확한 입력 snapshot과 평가 시각 리플레이, DB 업그레이드/불변 첫 수신/감사 기록, 시험 중 기존 예약 보호, 화면 꺼짐을 모의한 SensorEvent 주입, 모의 센서 재등록/세션 재개, 종료 미확인 꼬리 공백과 근거리 전송용 자료 제한을 포함합니다.

**실기기 검증 아님:** 모의 SensorManager/PowerManager와 주입한 이벤트는 Watch7의 실제 FIFO·wake-up 지원·절전·OS의 START_STICKY 복구·배터리·권한 상태를 검증하지 않습니다. 95구간 문제가 실제로 개선됐다는 결론도 아직 없습니다. 실제 절차는 [SENSOR_RELIABILITY.md](SENSOR_RELIABILITY.md)를 참고하세요.

기존 Release apk-build1006의 공개 인증서 지문을 고정했습니다. 새 서명 Release가 같은 인증서인지 GitHub Actions의 실제 apksigner 결과로 확인해야 합니다. 개인 키와 Secrets를 변경하거나 생성하지 않습니다. 로컬에서 운영 키로 서명했다고 주장하지 않습니다.

아래는 이전 버전 검증 기록입니다.

# 0.3.0 추가 검증 (2026-11-17 KST)

권한 진단과 업데이트 모듈 변경 후 로컬 자동 테스트 **299개, 실패/오류/건너뜀 0개**를 확인했습니다. 이전 285개를 유지하고 권한/설정 경로 5개, 업데이트 검증/승인/알람 보호/파일 공유 처리 9개를 추가했습니다.

| 범위 | 결과 |
|---|---|
| model / analysis / alarm / sensors / update | 16 / 235 / 29 / 10 / 9개 통과 |
| phone/wear assembleDebug 및 assembleRelease | 성공 (서명 경로는 개발용 시험 키) |
| phone/wear/core:alarm/core:update lintDebug | Error 0, Warning 남음 |
| actionlint / Bash 구문 / 배포 Python 구문 | 통과 |

추가 시험은 정확한 알람과 알림의 분리, 알림 차단 상태의 정시 예약, 설정 Activity 미지원/접근 차단, 알람 채널 차단 표시, 서명/버전/기기 종류 불일치, 전송 크기 초과/누락, 배포 주소 제한, 승인 대기/취소/성공/오래된 세션 응답을 포함합니다. JVM 모의이며 실제 Watch7이나 실제 Android 설치 UI 시험은 아닙니다.

실제 기기의 권한 상태·설치자·기존 서명·설정 Activity는 아직 관측하지 않았습니다. GitHub Secrets 등록 및 고정 서명 Release의 실배포는 별도 확인이 필요합니다. 로컬 서명 경로 검사는 개발용 시험 키로 수행하며 운영 키 등록을 뜻하지 않습니다. 사용자 기상 효과와 Wear OS 기본 설치 UI 지원도 미검증입니다. 전체 절차와 제약은 [UPDATES.md](UPDATES.md)를 참고하세요.

아래는 0.2.0 기반 검증 기록입니다. verification의 XML/변경된 lint 파일은 0.3.0 실행 결과로 갱신했습니다.

## 0.2.0 실행한 검증과 남은 시험

검증 버전: **0.2.0 / versionCode 3**. 검증일: **2026-11-17 KST**. Linux Cloud 개발 환경에서 소스를 생성하고 직접 빌드했습니다. Galaxy Watch7·휴대전화·Wear OS 에뮬레이터에서 앱을 실행한 결과는 포함하지 않습니다.

## 실행 결과

| 항목 | 결과 | 범위 |
|---|---|---|
| `:phone:assembleDebug` | 성공 | 휴대전화 debug APK 패키징 |
| `:wear:assembleDebug` | 성공 | 워치 debug APK 패키징 |
| `:core:model:test` | 16/16 통과 | 기존 9개 + 예열/판단 분리, 백업 옵션, 조기 snooze 상한, 날짜 배정, 불완전 수면 기록 |
| `:core:analysis:test` | 235/235 통과 | 기존 166개 유지 + 적응형 14개 + 51개 범위별 판단 시험 + 개인화 4개 |
| `:core:alarm:testDebugUnitTest` | 24/24 통과 | 기존 16개 + 백업 보존/취소, snooze 재부팅 중복 방지, 배정 유지, JSON 호환, 평가 병합, 휴대전화·센서 미의존 8개 |
| `:core:sensors:testDebugUnitTest` | 10/10 통과 | 기존 권한 정책 7개 + DB 마이그레이션·품질/수신시각 보존·심박 없는 움직임 권한 3개 |
| `:phone:lintDebug`, `:wear:lintDebug` | 성공, Error 0 | 앱 정적 검사. Warning은 남음 |
| 모든 Android `core` 모듈 `lintDebug` | 성공, Error 0 | alarm/sensors/health/settings/sync. Warning은 남음 |
| 0.2.0 변경 후 최종 빌드·lint | 성공 | 두 APK 재생성, 알람/센서/설정/sync 모듈과 두 앱 검사 |
| `./gradlew --version` 및 Wrapper 최종 앱 빌드·lint | 성공 | 고정 SHA-256의 Gradle 8.13을 받아 두 APK 재생성, Fragment 1.8.9 조합 검사 통과 |
| APK 서명 검사 | 양쪽 성공, 동일 인증서 | Android SDK `apksigner verify --print-certs` |
| 워치 APK metadata 검사 | 성공 | applicationId `dev.sleepalarm.app`, compile 36 / min 30 / target 35 |

자동 테스트는 합계 **285개, 실패 0개, 건너뜀 0개**입니다. 실제 알람 전달 지연·센서 정확도·기상 효과를 검증하는 시험은 아닙니다.

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
- 환경에 초기 Android SDK/JDK compiler가 없어 작업 영역 안에 개발 도구를 설치해 사용했습니다. 이 도구들은 배포 소스에 포함하지 않습니다.
- Cloud 환경의 읽기 전용 사용자 디렉터리 때문에 Gradle/Android 캐시는 작업 영역에 지정했습니다. Robolectric의 Android-all API 34/35 jar는 테스트 전용 외부 init script로 오프라인 경로를 지정했습니다. 일반 개발 머신에서는 Gradle과 Robolectric이 필요한 의존성을 자동으로 내려받습니다.
- 이 환경의 proxy/cache 경로를 프로젝트 Gradle 설정에 하드코딩하지 않았습니다. 소스의 `local.properties`도 제외합니다.

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
- 적응형 스마트 기상 규칙의 오탐·개운함 개선·졸림 감소·개인화 효과.
- 날짜별 교대/무작위 시험의 실제 사용자 행동·조건 차이·보고 편향.
- ACTIVITY_RECOGNITION만 허용한 Watch7에서 health FGS와 가속도 전달이 유지되는지.

[실기기 시험 계획](DEVICE_TEST_PLAN.md)에 항목별 절차와 기대 결과를 정리했습니다. 실기기 검증 전에는 알람 신뢰성이나 스마트 기상 효과가 입증되었다고 해석하지 마세요.

## 0.2.0 비교 검증의 해석

기존 ConservativeWakeEstimator 함수는 비교용으로 보존했습니다. 기존 테스트는 삭제하지 않았으며, 조기 snooze의 최종 시각 상한을 요구한 새 정책에 맞게 해당 상태 전이 기대값을 수정했습니다.

새 테스트는 심박 없음/지연/저품질, 미래 수신 시각 배제, 단발 충격, 정지 상태, 품질 공백, 기준선 미달, 시간 접근 시 보수성, 10~60분 경계, 관측 종료 후 미추정, 8회 미만 기본값, 미래 평가 제외를 포함합니다. 모의 권한·서비스·알람 테스트는 Watch7 자체의 백그라운드 정책이나 알람 실제 출력 검증이 아닙니다.

실제 기상 효과를 측정한 사용자 데이터는 아직 없습니다. 합성 데이터의 조기 판단이나 두 함수의 선택 시각 차이가 개선 효과를 입증하지 않습니다. 자세한 설계와 연구 근거는 ADAPTIVE_ALGORITHM.md를 참고하세요.
