# 휴대전화에서 APK 다운로드

## 현재 0.1.1 APK와 ZIP

| 파일 | 실제 크기 | MB (1,000,000바이트 기준) |
|---|---:|---:|
| phone-debug.apk | 32,260,233바이트 | 32.260 MB |
| wear-debug.apk | 30,619,432바이트 | 30.619 MB |
| galaxy-watch7-sleep-alarm-0.1.1-apks.zip | 21,284,639바이트 | 21.285 MB |

APK 전용 ZIP에는 **phone-debug.apk와 wear-debug.apk 두 파일만** 들어 있습니다. 소스·SDK·로그·서명 키를 포함하지 않습니다. ZIP 안의 APK는 기존 0.1.1 빌드와 SHA-256이 같습니다. 앱 기능과 APK 서명을 변경하지 않았습니다.

Codex 대화에서 APK 전용 ZIP을 다운로드한 뒤 휴대전화의 파일 관리자로 압축을 풀면 됩니다. 여기서도 다운로드가 제한되면 아래 GitHub Actions 경로를 사용할 수 있습니다.

## GitHub에 워크플로 준비

워크플로: [.github/workflows/build-apks.yml](../.github/workflows/build-apks.yml).

1. GitHub 저장소에 **프로젝트 내용 전체**를 올립니다. 저장소 루트에 `settings.gradle.kts`, `gradlew`, `gradle/`, `phone/`, `wear/`, `core/`, `.github/workflows/build-apks.yml`이 있어야 합니다. 소스 ZIP 파일 하나를 업로드하는 것만으로는 빌드되지 않습니다. `.github`는 숨김 폴더일 수 있으니 누락하지 마세요.
2. `local.properties`, `build/`, `.gradle/`, 서명 키를 업로드하지 않습니다. 저장소의 기존 `.gitignore`를 사용하세요.
3. 워크플로 파일이 저장소의 기본 브랜치에 있어야 수동 **Run workflow** 버튼을 사용할 수 있습니다. 저장소 설정에서 Actions 사용도 허용되어 있어야 합니다. 조직에서 외부 Action을 제한하면 아래 Actions를 허용해야 합니다.
4. GitHub에 push하면 자동 실행됩니다. 수동 실행도 지원합니다.

이 작업에서는 저장소 주소가 제공되지 않았고 GitHub에 push하거나 Actions를 실행하지 않았습니다. 저장소에 파일을 올린 후 아래 순서로 실행하세요.

## 휴대전화의 실제 다운로드 경로

휴대전화 Chrome/Samsung Internet에서 GitHub에 로그인한 뒤:

**저장소 → Actions → Build phone and watch APKs → Run workflow → 브랜치 선택 → Run workflow**

실행이 완료되고 녹색 성공 표시가 나오면:

**해당 실행 → Summary → 아래쪽 Artifacts → phone-debug / wear-debug**

- `phone-debug`를 누르면 GitHub가 만든 `phone-debug.zip`을 다운로드합니다. 압축을 풀면 `phone-debug.apk`가 나옵니다.
- `wear-debug`를 누르면 `wear-debug.zip`을 다운로드합니다. 압축을 풀면 `wear-debug.apk`가 나옵니다.
- 같은 실행의 Summary에는 두 Artifact 직접 링크와 APK 크기·SHA-256도 표시됩니다.
- 모바일 화면에 메뉴가 보이지 않으면 브라우저의 **데스크톱 사이트**를 켜세요. Actions 또는 실행 상세 페이지가 앱으로 전환되면 브라우저에서 다시 여세요.
- Artifact 다운로드에는 GitHub 로그인과 해당 저장소 접근 권한이 필요합니다. 만료된 Artifact는 워크플로를 다시 실행해 생성하세요. 보관 기간은 저장소의 Artifact 정책을 따릅니다.

Artifact는 별도 Release나 공개 파일 서버가 아닙니다. 워크플로 실행 전에는 다운로드할 Artifact가 없습니다.

## 빌드 환경과 서명

- JDK 17(Temurin), Android SDK Platform 36, 기존 AGP에 맞는 Build Tools 35.0.0을 설치합니다.
- 프로젝트의 **기존 Gradle Wrapper**와 SHA-256 고정을 사용합니다.
- `./gradlew --no-daemon --stacktrace :phone:assembleDebug :wear:assembleDebug`로 두 앱을 같은 작업에서 빌드합니다.
- `actions/upload-artifact@v4`를 각각 호출해 두 APK를 별도 Artifact로 업로드합니다. 생성 파일이 없으면 업로드 단계가 실패합니다.
- 기존 `assembleDebug` 기본 서명 설정을 그대로 사용합니다. `signingConfigs`나 앱 코드·권한·버전은 바꾸지 않았으며, 키를 저장소에 넣거나 서명 Secret을 요구하지 않습니다.
- **동일한 Actions 실행에서 받은 두 APK를 함께 사용하세요.** 같은 실행에서는 두 앱이 같은 기본 debug 키를 사용합니다. GitHub의 임시 실행 환경에서 생성되는 debug 키는 이전 Codex 빌드나 다른 Actions 실행의 키와 다를 수 있습니다. 이 경우 기존 설치본 위에 업데이트가 거부될 수 있습니다. 기존 앱을 제거하면 앱 데이터도 삭제되므로 필요한 기록을 보존한 뒤 처리하세요.
- 휴대전화 APK는 휴대전화에, 워치 APK는 워치에 설치합니다. 휴대전화에 두 APK를 모두 설치하는 방식이 아닙니다. 워치 설치는 [README](../README.md)의 ADB 절차를 따릅니다.

## 이 변경에서 확인한 범위

APK 전용 ZIP의 파일 목록·CRC·압축 해제 후 SHA-256을 검사했습니다. `actionlint 1.7.7` 검사 오류 0개, 워크플로 필수 조건 및 내장 Python 구문 검사 통과, JDK 17·SDK 36의 로컬 Wrapper 빌드 성공(178개 작업 up-to-date)을 확인했습니다. GitHub 서버에서의 Actions 실행이나 휴대전화 브라우저의 실제 다운로드 성공은 확인하지 않았습니다.
