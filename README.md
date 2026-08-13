# 일루전 라이브 알리미

`illusionlive.com` 공식 RSS를 확인해 새 게시글을 Windows 알림으로 알려 주는 트레이 앱입니다.

![앱 화면](docs/screenshot.png)

## 기능

- 새 글 자동 확인 및 Windows 알림
- 게시판별 알림 켜기/끄기
- 게시판별 제목·작성자 키워드 필터
- 백그라운드에서 새 글 자동 확인
- Windows 로그인 시 자동 실행
- 알림이나 최근 글을 눌러 원문 열기
- 창을 닫아도 알림 영역에서 계속 실행
- 첫 실행 시 기존 글은 알리지 않고 기준점만 저장

## 실행

1. `dist/IllusionLiveNotifier.exe` 실행
2. **게시판별 알림** 탭에서 원하는 게시판과 키워드 설정
3. **설정 저장** 클릭

Windows SmartScreen이 표시될 수 있습니다. 개인 코드 서명 인증서로 서명하지 않은 로컬 빌드이기 때문입니다. 출처를 확인한 뒤 실행하거나 아래 명령으로 직접 빌드하세요.

완전 종료: 작업 표시줄 알림 영역의 종 아이콘 우클릭 → **종료**

## 직접 빌드

요구 사항: Windows 10/11 x64, .NET 10 SDK

```powershell
./build.ps1
```

검증:

```powershell
dotnet run --project ./IllusionLiveNotifier -c Release -p:OutputType=Exe -- --self-test
dotnet run --project ./IllusionLiveNotifier -c Release -p:OutputType=Exe -- --check-live
```

## 데이터

- 읽기: `https://www.illusionlive.com/rss`
- 로컬 설정: `%LOCALAPPDATA%\IllusionLiveNotifier\settings.json`
- 별도 서버, 계정 수집, 추적 없음

사이트 RSS에 노출되지 않는 비공개·회원 전용 글은 감지할 수 없습니다.
