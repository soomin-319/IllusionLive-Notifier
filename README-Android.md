# 일루전 라이브 알리미 (Android)

`https://www.illusionlive.com/rss`의 새 게시글을 확인해 Android 알림으로 보여 줍니다.

## 설치

1. `IllusionLiveNotifier.apk`를 Android 기기로 복사해 실행
2. 요청되면 **이 출처의 알 수 없는 앱 설치** 허용
3. 앱 실행 후 **알림 권한** 허용

요구 버전: Android 8.0 (API 26) 이상.

## 기능

- 첫 실행 시 사용법 안내
- 최근 글 목록 및 원문 열기
- 다크 모드 (기본값은 시스템 설정을 따름)
- 게시판별 알림 켜기/끄기
- 제목·작성자 키워드 필터 (쉼표로 OR)
- 앱을 닫아도 백그라운드에서 새 글 자동 확인
- 첫 실행 시 기존 글은 알림하지 않고 기준으로만 저장
- 별도 서버·계정·추적 없음

Android 절전 정책에 따라 백그라운드 확인 시각이 늦어질 수 있습니다. RSS에 나오지 않는 비공개·회원 전용 글은 감지할 수 없습니다.

APK는 로컬 자체 서명 빌드입니다. Play Store 배포본이 아닙니다.

## 직접 빌드

JDK 17, Android SDK Platform/Build Tools 36 필요.

```powershell
./build-android.ps1
```
