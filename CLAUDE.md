# CLAUDE.md

IllusionLive Notifier — `https://www.illusionlive.com/rss` 새 글 알림. Android 앱(주)과 Windows WinForms 앱.

- `IllusionLiveNotifier.Android/` — Android 앱 (Java, SDK 없이 aapt2/d8/apksigner 직접 호출)
- `IllusionLiveNotifier/` — Windows .NET WinForms 앱
- `build-android.ps1` / `build.ps1` — 빌드 스크립트
- 원격: `origin` = https://github.com/soomin-319/IllusionLive-Notifier.git

## 빌드

```powershell
./build-android.ps1   # Android APK (JDK 17, Android SDK Platform/Build Tools 36 필요)
./build.ps1           # Windows exe (dotnet publish, self-contained)
```

## 규칙: 파일 변경 시 PR 생성

프로젝트 파일을 수정하면 작업 완료 후 반드시 PR까지 만든다. `main`에 직접 커밋하지 않는다.

1. 작업 브랜치 생성 (`git checkout -b <type>/<short-name>`)
2. 변경 사항 커밋 — 커밋 메시지는 Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:` …)
3. `git push -u origin <branch>`
4. `gh pr create` 로 PR 생성 — 제목은 커밋 타입 접두사 유지, 본문에 변경 요약과 검증 방법 기재

PR 생성 전 관련 빌드/테스트가 통과하는지 확인한다 (Android는 `./build-android.ps1` 이 `SelfTest` 를 함께 실행).

## 규칙: APK 빌드 산출물은 다운로드 폴더에 압축 저장

앱 APK를 빌드하면 결과물을 사용자 다운로드 폴더(`$env:USERPROFILE\Downloads`)에 zip으로 압축해 저장한다.

```powershell
./build-android.ps1
$dl = Join-Path $env:USERPROFILE 'Downloads'
Compress-Archive -Path 'android-dist\*' -DestinationPath (Join-Path $dl 'IllusionLiveNotifier-android.zip') -CompressionLevel Optimal -Force
```

- 저장 이름: `IllusionLiveNotifier-android.zip` (Windows 빌드는 `IllusionLiveNotifier-win-x64.zip`)
- 기존 파일이 있으면 덮어쓴다 (`-Force`)
- 저장 후 zip 경로와 SHA256 해시를 사용자에게 알린다

## 참고

- APK는 로컬 자체 서명 빌드. 키스토어: `C:\Android\illusion-tools\keys\illusionlive-notifier.p12`
- aapt2가 비ASCII 경로에서 실패하므로 빌드는 `C:\Android\illusion-tools\app-build` 에서 수행
- 최소 지원: Android 8.0 (API 26)
