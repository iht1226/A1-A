# 0.3.0 권한 진단과 개인용 GitHub 업데이트

## 실제 확인 상태

코드는 구현했지만 실제 Galaxy Watch7 권한 상태·설치 출처·OS 설치 UI를 관측하지 않았습니다. 문제 해결이나 사용자 승인만으로 워치 설치가 가능하다고 단정하지 않습니다. 아래의 자동 배포는 **고정 서명 Secrets 등록 이후** 활성화됩니다. Secrets가 없으면 기존 debug APK 빌드/테스트만 실행하고 Release 배포는 명시적으로 건너뜁니다.

## 정확한 알람과 알림을 구분

이 앱은 알람 시계가 주기능입니다. [공식 Android 설명](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)에 따라 API 33 이상은 `USE_EXACT_ALARM` 일반 설치 권한, API 31/32는 maxSdkVersion=32인 `SCHEDULE_EXACT_ALARM` 특별 접근을 사용합니다. minSdk/targetSdk/알람 API와 기상 알고리즘은 바꾸지 않습니다. `canScheduleExactAlarms()` 응답을 계속 확인하며 권한을 얻었다고 무조건 가정하지 않습니다.

워치 설정 탭에서 정확한 알람 API 응답, 설치 권한, POST_NOTIFICATIONS, 앱 알림 설정, 기상 알람 채널 차단, 전체 화면 접근을 각각 표시합니다. 알림 차단을 정확한 알람 거부로 표시하거나 예약을 차단하지 않습니다. 알림이 꺼져 있으면 해제/다시 알림 화면과 대체 알림이 제한될 수 있습니다. 소리·진동 출력까지 보장하지 않습니다.

전용 설정 → 앱 상세 → 일반 설정 순서로 시도하고 각 단계의 ActivityNotFoundException/SecurityException 등을 처리합니다. 모두 없으면 직접 설정하는 안내를 표시합니다. API 33 이상에는 SCHEDULE_EXACT_ALARM 특별 허용 화면을 요청하지 않습니다. 버전/업데이트 화면에 설치 버전·API·설치자·실제 서명 지문을 표시합니다.

## 최초 전환: 이전 Actions 서명을 복구할 수 없음

0.2.0 Actions는 실행마다 기본 debug 키를 만들었고 개인 키를 보관하지 않았습니다. APK의 공개 인증서로 개인 키를 복원할 수 없습니다. 따라서 현재 설치된 0.2.0 APK에 새로운 고정 키로 서명한 APK를 덮어쓰는 것은 불가능합니다. 이는 앱 코드로 우회할 수 없는 Android 업데이트 검증입니다.

- 당시 실제 개인 키가 있다면 그 키를 사용해야 기존 앱을 삭제하지 않고 갱신할 수 있습니다.
- 개인 키가 없다면 고정 서명으로 최초 전환하는 설치는 별도 마이그레이션이 필요합니다. 앱이 자동으로 기존 앱을 삭제하거나 데이터를 지우지 않습니다. 기존 기록을 보존할 방법을 먼저 결정하세요. 현재 버전은 전체 앱 데이터 내보내기/가져오기를 제공하지 않습니다.
- 휴대전화와 워치 모두 같은 고정 키의 초기 설치본이 필요합니다. 0.2.0에는 새 수신 서비스도 없으므로 새 업데이트 전송으로 스스로 부트스트랩하지 못합니다.
- 이 최초 전환 이후부터 동일 키·패키지·상승 버전으로 업데이트하면 기존 앱 데이터를 유지할 수 있습니다. [공식 앱 서명 설명](https://developer.android.com/studio/publish/app-signing)을 참고하세요.

## 고정 서명을 Secrets에 등록

연결된 GitHub 도구는 Secrets 관리 API를 제공하지 않습니다. 아직 키를 생성·등록하거나 서명 Release가 배포됐다고 주장하지 않습니다. 저장소 관리자만 신뢰할 수 있는 컴퓨터에서 다음을 한 번 실행하세요. 비밀 값은 대화나 저장소에 붙여넣지 마세요.

```bash
# JDK 17 keytool, GitHub CLI 필요. gh auth login 후 실행
bash tools/configure-signing.sh /안전한/백업경로/sleep-alarm.p12
```

스크립트는 새 PKCS12 키를 만들거나 해당 경로의 기존 `sleepalarm` alias 키를 사용하고 암호를 숨겨서 입력받습니다. GitHub CLI의 표준입력으로 아래 4개 Repository Secret을 등록합니다. 기존 키의 alias/암호가 다르면 직접 Settings에서 해당 값으로 등록하세요.

- `ANDROID_KEYSTORE_BASE64`: keystore 파일을 base64 인코딩한 값.
- `ANDROID_KEYSTORE_PASSWORD`: keystore 암호.
- `ANDROID_KEY_ALIAS`: 키 alias.
- `ANDROID_KEY_PASSWORD`: 개인 키 암호.

수동 등록 경로: [저장소 Actions Secrets](https://github.com/iht1226/A1-A/settings/secrets/actions) → New repository secret. 키 파일과 암호는 별도로 오프라인 백업하세요. 잃으면 이후 같은 서명 업데이트를 만들 수 없습니다. keystore/base64/암호 파일은 커밋하지 않습니다.

등록 후 Actions → Build phone and watch APKs → Run workflow → main을 실행합니다. `Inspect fixed signing configuration` 결과와 `Publish fixed-signature update APKs` 성공을 확인하세요. 일부 Secret만 있으면 실패시키고, 모두 없으면 서명 배포를 건너뜁니다. 잘못된 키/암호는 서명 빌드가 실패하며 debug 키로 대체 배포하지 않습니다.

## Actions 배포 구조

JDK 17, SDK 36, 기존 Wrapper를 사용합니다. main push 및 workflow_dispatch를 지원합니다. 기존 테스트와 업데이트 테스트, phone/wear debug 빌드가 성공한 뒤 고정 키가 있을 때만 별도 release 빌드를 합니다. 버전 코드는 `1000 + github.run_number`로 양쪽에 동일하게 적용해 새 실행마다 증가시킵니다. 로컬 기본 버전은 0.3.0/code 4입니다. 새 workflow 파일로 바꾸거나 다른 workflow로 빌드할 때도 기존 배포보다 버전 코드가 커야 합니다.

개인 키는 임시 경로에 권한을 제한해 복원하고 두 APK를 서명합니다. `apksigner verify`와 양쪽 인증서·패키지·기기 종류·버전 일치를 확인합니다. 성공 후 공개 Release에 `phone-update.apk`, `wear-update.apk`, `update.json`을 함께 올립니다. 같은 버전의 파일은 덮어쓰지 않습니다. 임시 키는 always 정리하고 Artifact/Release/로그에 올리지 않습니다. Repository GITHUB_TOKEN은 배포 job에서만 contents:write입니다.

기존 `phone-debug`/`wear-debug` Artifact는 개발 검사 용도로 유지합니다. Secrets가 등록되면 이 두 빌드도 같은 고정 키로 서명합니다. Secrets가 없을 때만 이전 기본 임시 debug 키를 사용하며 자동 업데이트용으로 배포하지 않습니다. 자동 업데이트는 Release의 `*-update.apk`와 update.json을 사용하세요.

## 휴대전화에서 사용하는 경로

1. 앱 상단 ‘앱 업데이트’를 엽니다. ‘Wi-Fi에서 자동 확인·다운로드’를 한 번 켭니다. 기본은 꺼짐입니다.
2. WorkManager가 약 12시간마다 비종량제 네트워크와 배터리 조건에서 공개 Release를 확인합니다. Doze/강제 중지/네트워크에 따라 늦어질 수 있습니다. ‘지금 확인’은 사용자가 즉시 요청합니다.
3. 휴대전화가 두 APK를 내부 저장소로 받아 선언 크기·SHA-256·실제 패키지/서명/기기 종류/버전을 검증합니다. 실패·서명 변경·다운그레이드는 차단합니다. GitHub 토큰을 앱에 넣지 않습니다.
4. 검증한 워치 APK를 Data Layer Asset으로 전송 요청합니다. 워치 연결이 끊기면 버퍼링될 수 있고 수신·검증 확인은 별도 상태로 표시합니다. 전송 요청 성공은 설치 성공이 아닙니다. 수동 전송/재시도 버튼도 있습니다.
5. 워치에서 알림 또는 설정 → 앱 버전·업데이트를 열어 외부 설치 허용과 설치 요청을 진행합니다. 휴대전화도 같은 화면에서 설치 요청 후 Android 승인을 합니다. ‘설치 승인 화면’ 또는 알림으로 승인합니다. 앱은 백그라운드에서 승인 화면을 강제로 열지 않습니다.
6. PackageInstaller의 실제 STATUS_SUCCESS만 성공으로 기록합니다. 취소·실패·미지원은 별도 오류입니다. 앱이 죽었어도 승인 알림이 남을 수 있습니다. 알림이 차단되고 메모리의 승인 요청까지 사라졌다면 알림을 허용하고 설치 요청을 다시 해야 합니다.

[공식 Data Layer Asset](https://developer.android.com/training/wearables/data/sync), [PackageInstaller 사용자 승인](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int)), [WorkManager 주기적 작업](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)을 사용합니다. 세션·파일 작업은 UI 밖에서 처리하며 임의 root/기기 소유자/무승인 설치 권한은 요구하지 않습니다.

## Wear OS 제한과 수동 대안

Wear OS 기기/펌웨어에 따라 외부 앱 설치 설정이나 설치 UI가 없거나 차단될 수 있습니다. Android PackageInstaller API가 있다는 사실이 Watch7의 설치 UI 지원을 뜻하지 않습니다. 현재 기기에서 이 경로는 **미검증**입니다. OS가 차단하면 이 앱으로 사용자 승인만의 설치를 보장할 수 없습니다.

이 경우 휴대전화에 이미 받아 둔 APK를 ‘워치 APK 공유’로 전달해 ADB 설치 도구를 사용하거나, Google Play 비공개 테스트 배포를 별도로 구성해야 합니다. ZIP을 수동으로 찾고 풀 필요는 없지만 ADB 페어링·설치를 앱 안에 구현한 것은 아닙니다. 무선 디버깅 포트 우회나 비공개 설치 API는 사용하지 않습니다. 완전히 안정적인 소비자 자동 업데이트는 Google Play 배포가 더 적합하며 별도 개발자 계정/업로드 설정이 필요합니다. 이 개인용 GitHub 경로의 REQUEST_INSTALL_PACKAGES 자체 업데이트 구조를 그대로 Play 정책 적합하다고 주장하지 않습니다.

## 기존 알람 보호

core:update는 센서 추정과 독립된 모듈입니다. 다운로드/전송/설치 실패는 알람 예약을 취소하지 않습니다. 워치가 울리거나 다시 알림 중이거나 sensorStart 15분 전 이후면 설치 요청을 차단합니다. 사용자에게 기상 후 다시 시도하도록 안내합니다. 승인 알림도 앱 화면을 거쳐 제한 구간을 다시 검사합니다. 다만 OS 설치 승인 화면을 오랫동안 열어둔 뒤의 실제 설치 시각은 앱이 통제하지 못하므로 알람을 사용하는 밤에는 업데이트 승인을 미루세요. 멀리 남은 예약은 기존 MY_PACKAGE_REPLACED 복원 경로를 사용하지만 실제 OEM 앱 교체·부팅 중 전달은 미검증입니다. 설치·재부팅·방전·강제 중지 중 알람 출력을 보장할 수 없습니다.
