# 공식 API 조사와 제약

확인 기준일: 2026-11-17. 아래 링크는 구현과 버전 선택의 공식 근거입니다. 출처가 설명하는 API 지원과 실제 Galaxy Watch7에서의 동작 확인은 서로 다릅니다.

## 알람과 백그라운드

- [AlarmManager / setAlarmClock](https://developer.android.com/reference/android/app/AlarmManager#setAlarmClock(android.app.AlarmManager.AlarmClockInfo,%20android.app.PendingIntent)): 알람 시계 용도의 정확 예약. PendingIntent 기반으로 화면·앱 프로세스 생존과 분리.
- [정확 알람 예약 가이드](https://developer.android.com/develop/background-work/services/alarms): Android 12 이상에서 정확 알람 권한 확인, 권한 변경 시 재예약, 부팅 후 복원. 이 앱은 `SCHEDULE_EXACT_ALARM` 경로를 사용하며 `USE_EXACT_ALARM` 자동 허용 경로를 가정하지 않음.
- [Android 14 정확 알람 기본 거부](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms): 신규 설치 시 허용되어 있다고 가정하지 않음.
- [Doze와 App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby): `setAlarmClock`은 Doze에서도 실행 대상. 일반 `setExact`의 동일 동작을 가정하지 않음.
- [Foreground service 시작 예외](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): 사용자 요청 정확 알람에서 FGS 시작 경로 사용. 임의 백그라운드 서비스 실행이 항상 가능하다고 가정하지 않음.
- [Foreground service 유형](https://developer.android.com/develop/background-work/services/fgs/service-types): 센서는 `health`, 알람 소리 재생은 `mediaPlayback`과 각 권한 선언. 부팅 Receiver에서 곧바로 mediaPlayback 서비스를 실행하지 않고 정확 알람을 재예약.
- [Direct Boot](https://developer.android.com/privacy-and-security/direct-boot): 알람 계획만 Device Protected Storage에 보관, 건강 관측은 잠금 해제 후 Credential Protected Storage에 보관.
- [시간 민감 알림](https://developer.android.com/develop/ui/views/notifications/time-sensitive): 전체 화면 알람은 시스템 접근 허용과 사용자 설정 영향을 받음.

## Wear OS 센서

- [Health Services](https://developer.android.com/health-and-fitness/health-services): 운동을 가장하지 않고 장시간 관측 용도에 PassiveMonitoringClient 사용.
- [백그라운드 수동 관측](https://developer.android.com/health-and-fitness/health-services/monitor-background): service 전달은 배치 가능. 수신 주기 보장이나 실시간 전송을 가정하지 않음. capabilities와 실제 측정 timestamp 사용.
- [기기 호환성](https://developer.android.com/health-and-fitness/health-services/compatibility): 지원되는 데이터 유형을 런타임에 검사.
- [백그라운드 신체 센서 권한](https://developer.android.com/health-and-fitness/health-services/background-body-sensors): `BODY_SENSORS` 먼저, `BODY_SENSORS_BACKGROUND` 별도 요청. 제한된 권한의 설치자·사용자 허용 조건을 고려. 실패 시 일반 알람 제공.
- [Wear OS 6 권한 변경](https://developer.android.com/health-and-fitness/health-services/permissions): target 36 이상에서 건강 권한 체계로 전환 필요. 현재 target 35는 BODY_SENSORS 경로를 명시적으로 사용.
- [SensorEvent.timestamp](https://developer.android.com/reference/android/hardware/SensorEvent#timestamp): 부팅 이후 시간 기준. 콜백 도착 시간과 다름.
- [센서 배치](https://source.android.com/docs/core/interaction/sensors/batching): 일반 센서는 수면 상태의 AP를 깨우지 않을 수 있음. MCU 배치/센서 종류에 따라 전달 공백 가능.

## 삼성 헬스와 Health Connect

- [삼성 공식 수면 데이터 연동 예제](https://developer.samsung.com/health/blog/en/managing-sleep-data-with-samsung-health-and-health-connect): Samsung Health → Health Connect 수면 세션과 단계 기록 사용.
- [삼성 Health Connect FAQ](https://developer.samsung.com/health/health-connect-faq.html): 사용자의 데이터 공유 허용·앱 동기화가 필요함.
- [수면 세션](https://developer.android.com/health-and-fitness/health-connect/features/sleep-sessions): SleepSessionRecord와 STAGE_TYPE_*를 사용. 단계 목록이 비어 있는 경우를 처리.
- [수면 경험 구현](https://developer.android.com/health-and-fitness/health-connect/experiences/sleep): 종료된 수면 세션을 읽는 기록 기반 경로. 완료된 기록을 실시간 수면 단계 피드로 취급하지 않음.
- [Health Connect 시작](https://developer.android.com/health-and-fitness/health-connect/get-started): provider 상태와 수면 읽기 권한, rationale/permission usage Activity 선언.
- [데이터 읽기](https://developer.android.com/health-and-fitness/health-connect/read-data): 기간 필터와 페이지네이션. 초기 읽기 가능 기간을 무제한으로 가정하지 않음. 이 앱은 최근 7일만 요청하며 백그라운드/전체 과거 기록 권한을 요청하지 않음.

Health Connect는 이 MVP에서 **휴대전화에만** 탑재합니다. 삼성 헬스의 공개 실시간 수면 단계 API가 있다는 가정, 비공개 삼성 센서 API, Samsung Health SDK의 별도 파트너 권한에 의존하는 코드는 없습니다.

## 전송과 버전

- [Wearable Data Layer](https://developer.android.com/training/wearables/data/overview): Google Play Services 기반, 같은 앱 ID와 서명. 연결되어 있지 않을 때 명령 성공을 표시하지 않음.
- [MessageClient](https://developer.android.com/training/wearables/data/messages): 연결 상태의 단발 명령, 별도 앱 ACK로 실제 예약 완료 확인.
- [DataClient](https://developer.android.com/training/wearables/data/data-items): 요약 이력의 영속 동기화. 즉시 전송을 보장하지 않음.
- [AGP 8.13 호환성](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Kotlin Compose plugin](https://developer.android.com/develop/ui/compose/compiler), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Wear Compose](https://developer.android.com/jetpack/androidx/releases/wear-compose), [Health Connect 릴리스](https://developer.android.com/jetpack/androidx/releases/health-connect), [Health Services 릴리스](https://developer.android.com/jetpack/androidx/releases/health).
