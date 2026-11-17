# 0.3.0 APK 받기와 자동 업데이트

고정 서명 Secrets가 등록되어 성공적으로 배포된 버전은 [GitHub Releases](https://github.com/iht1226/A1-A/releases/latest)의 `phone-update.apk`/`wear-update.apk`를 사용합니다. 초기 설치 후 휴대전화 앱의 ‘앱 업데이트’에서 자동 확인을 켤 수 있습니다. 매번 Artifact ZIP을 수동으로 풀지 않고 앱이 APK를 다운로드하고 워치로 전송합니다.

**현재 고정 서명 키 등록은 별도 필요하며, 이전 0.2.0 Actions 임시 서명과 새 키는 호환되지 않을 수 있습니다.** 워치의 외부 설치 UI도 미검증입니다. 최초 전환·Secrets·사용자 승인·ADB 대안은 [UPDATES.md](UPDATES.md)를 먼저 확인하세요.

[Actions](https://github.com/iht1226/A1-A/actions/workflows/build-apks.yml)의 `phone-debug`/`wear-debug` Artifact는 계속 생성합니다. GitHub 로그인 → 성공 실행 → Summary → Artifacts에서 각각 내려받아 압축을 풀면 APK가 나옵니다. 모바일 메뉴가 안 보이면 브라우저의 데스크톱 사이트를 켜세요. Secrets가 없으면 이 debug APK는 임시 서명입니다. Secrets 등록 후에는 debug APK도 고정 키를 쓰지만 자동 업데이트는 Release의 `*-update.apk`를 사용하세요.

서명 배포 job은 `phone-update`/`wear-update` Artifact도 각각 제공합니다. Secrets가 없으면 이 job과 Release 배포는 건너뜁니다. 앱의 기존 기능·applicationId는 유지하며 Secrets 등록 시 debug/release 모두 같은 고정 서명을 사용합니다. Secrets가 없으면 기본 debug 서명 설정만 유지합니다.
