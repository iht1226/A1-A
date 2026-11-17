# 휴대전화에서 APK 다운로드

현재 소스는 **0.2.0 / versionCode 3**입니다. [iht1226/A1-A](https://github.com/iht1226/A1-A)의 main 브랜치에 전체 소스와 워크플로를 관리합니다. Codex의 APK/ZIP 다운로드 링크 대신 GitHub Actions Artifact를 사용합니다.

## 휴대전화의 다운로드 경로

1. Chrome/Samsung Internet에서 GitHub에 로그인합니다.
2. [Build phone and watch APKs](https://github.com/iht1226/A1-A/actions/workflows/build-apks.yml)를 열고 원하는 커밋의 **녹색 성공 실행**을 선택합니다.
3. 실행의 **Summary → Artifacts → phone-debug / wear-debug**를 각각 누릅니다.
4. GitHub가 제공하는 각각의 ZIP을 풀면 `phone-debug.apk`와 `wear-debug.apk`가 나옵니다. Summary에도 Artifact 링크와 APK 크기·SHA-256이 표시됩니다.

모바일 화면에서 메뉴가 보이지 않으면 브라우저의 **데스크톱 사이트**를 켜세요. GitHub 앱으로 전환되면 브라우저에서 다시 여세요. 실제 휴대전화 브라우저 다운로드는 아직 검증하지 않았습니다.

Artifact에는 보관 기간이 있습니다. 만료되면 **Run workflow → main → Run workflow**로 다시 빌드하세요. main에 push하면 자동으로 실행되며 수동 실행도 지원합니다. 다운로드에는 GitHub 로그인이 필요합니다.

## 빌드와 검사

워크플로: [.github/workflows/build-apks.yml](../.github/workflows/build-apks.yml).

- Temurin JDK 17, Android SDK Platform 36, Build Tools 35.0.0.
- 기존 Gradle Wrapper와 배포 SHA-256 고정을 사용합니다.
- `:phone:assembleDebug :wear:assembleDebug`로 두 APK를 빌드합니다.
- model/analysis/alarm/sensors 자동 테스트가 모두 성공해야 업로드합니다.
- `actions/upload-artifact@v4`로 휴대전화·워치 APK를 각각 업로드합니다. 파일 누락은 실패로 처리합니다.
- `local.properties`, 빌드 캐시, 서명 키는 저장소에 포함하지 않습니다.

로컬 검증 결과는 [VALIDATION.md](VALIDATION.md)에 기록합니다. 해당 커밋의 실제 GitHub 빌드 상태는 Actions 실행 화면에서 확인하세요.

## 설치와 서명

기존 `assembleDebug`의 기본 서명 설정을 유지하며 별도 서명 Secret은 요구하지 않습니다. **같은 Actions 실행의 두 APK를 함께 사용하세요.** 임시 실행 환경의 debug 키는 이전 Codex 빌드나 다른 실행과 다를 수 있어 기존 앱 위에 업데이트가 거부될 수 있습니다. 앱 제거는 기존 데이터를 삭제하므로 먼저 기록을 보존하세요.

휴대전화 APK는 휴대전화에, 워치 APK는 워치에 설치합니다. 워치 설치는 [README](../README.md)의 ADB 절차를 따릅니다. 실제 Watch7 설치·알람·기상 효과는 미검증입니다.
