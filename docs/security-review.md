# 보안 점검 보고서

- 대상: `IllusionLiveNotifier.Android` (Java), `IllusionLiveNotifier` (.NET WinForms), `build-android.ps1`, `build.ps1`
- 기준 커밋: `f7c41fc` (main)
- 점검일: 2026-09-02
- 이전 점검(`23d8110`, PR #16)에서 고친 항목은 [이미 막혀 있는 것](#이미-막혀-있는-것)에 정리했고, 이 문서는 **아직 남아 있는 것**만 다룬다.

앱은 서버·계정·추적이 없고 외부에서 들어오는 데이터가 RSS 피드 하나뿐이다. 그래서 위험은 대부분 "피드 내용이 앱을 어디까지 흔들 수 있는가"와 "빌드/배포 과정"에 몰려 있다. 원격 코드 실행이나 데이터 유출로 이어지는 결함은 발견되지 않았다.

## 요약

| # | 심각도 | 위치 | 문제 | 확인 |
|---|--------|------|------|------|
| [1](#1-피드-글-하나가-전체-알림을-멈출-수-있다) | 중 | `FeedParser.java:56,178` | CDATA 안의 `<!DOCTYPE` 문자열이 피드 전체 파싱을 중단시킨다 | 재현됨 |
| [2](#2-피드-링크가-캐시-구분자를-주입할-수-있다) | 중 | `FeedParser.java:123,88,99` | 링크 경로의 `%1F`/`%1E`가 캐시 구분자와 충돌해 다른 글까지 사라진다 | 재현됨 |
| [3](#3-서명-비밀번호가-명령줄-인자로-노출된다) | 중 | `build-android.ps1:102,109-110` | 키스토어 비밀번호를 명령줄 인자로 넘겨 같은 PC의 다른 프로세스가 읽을 수 있다 | 코드 확인 |
| [4](#4-windows-쪽에만-응답-크기-제한이-없다) | 하 | `RssFeedClient.cs:24` | Android에는 2 MB 상한이 있는데 Windows에는 없다 | 코드 확인 |
| [5](#5-android는-링크를-열-때-스킴을-확인하지-않는다) | 하 | `MainActivity.java:773` | Windows(`OpenUrl`)에 있는 스킴 검사가 Android에는 없다 | 재현됨 |
| [6](#6-windows-xxe-방어가-프레임워크-기본값에만-기대고-있다) | 하 | `RssFeedClient.cs:30` | DTD 차단이 `XDocument.Parse` 기본값 의존이고 테스트로 고정돼 있지 않다 | 코드 확인 |
| [7](#7-게시판-목록이-줄지-않고-늘기만-한다) | 하 | `FeedChecker.java:158,179` | `dynamic_boards`와 `board_enabled_*` 키가 정리되지 않는다 | 코드 확인 |
| [8](#8-사이트-사본이-무시-목록에-빠져-있다) | 하 | `.gitignore` | 저장소 루트의 사이트 사본 420 KB가 무시 목록에 없다 | 코드 확인 |
| [9](#9-windows-실행-파일에-서명이-없다) | 정보 | `build.ps1` | Authenticode 서명이 없어 변조 검증 수단이 없다 | 코드 확인 |
| [10](#10-openurl이-http도-받는다) | 정보 | `MainForm.cs:714` | 파서는 https만 내보내는데 여는 쪽은 http도 허용한다 | 코드 확인 |

---

## 1. 피드 글 하나가 전체 알림을 멈출 수 있다

**심각도: 중 (가용성)** · `FeedParser.java:56`, `FeedParser.java:178`

XXE를 막으려고 파싱 전에 바이트를 훑어 `<!DOCTYPE`가 있으면 예외를 던진다.

```java
static List<Post> parse(byte[] xml) throws Exception {
    if (hasDoctype(xml)) throw new IllegalArgumentException("DOCTYPE is not allowed");
```

`hasDoctype`은 XML 구조를 보지 않고 **파일 전체를 문자열로 만들어 부분 문자열을 찾는다.** 그래서 게시글 제목이 CDATA로 감싸여 있고 그 안에 `<!DOCTYPE` 이라는 글자가 들어 있으면, 그 글자는 문서의 DTD가 아니라 그냥 본문인데도 걸린다. RSS 제목을 CDATA로 내보내는 CMS는 흔하다.

실제로 재현했다. 정상 피드에 제목이 `<![CDATA[<!DOCTYPE html> explained]]>` 인 글 하나만 넣으면:

```
cdata hasDoctype  = true
cdata parse THREW = IllegalArgumentException: DOCTYPE is not allowed
```

글 하나 때문에 피드 전체가 버려진다. 그 글이 피드에서 밀려날 때까지 **모든 설치본이 새 글 알림을 전혀 받지 못한다.** 공격이랄 것도 없이 사이트에 그런 제목의 글이 한 번 올라오기만 하면 된다. 이스케이프된 `&lt;!DOCTYPE`는 걸리지 않으니 문제는 CDATA 경로에 한정된다.

**고칠 방향** — 검사 범위를 문서 앞부분(프롤로그)으로 좁힌다. DOCTYPE 선언은 루트 요소보다 앞에만 올 수 있으므로, 첫 `<` 부터 첫 요소 시작 태그 전까지만 보면 CDATA 본문은 애초에 사정권 밖이다. 자기 검사에 위 CDATA 피드를 넣어 "정상 파싱된다"를 고정한다.

## 2. 피드 링크가 캐시 구분자를 주입할 수 있다

**심각도: 중 (가용성)** · `FeedParser.java:123`, `FeedParser.java:88`, `FeedParser.java:99`

캐시는 ASCII 제어문자를 구분자로 쓰는 자체 포맷이다.

```java
private static final char UNIT = 0x1f;
private static final char RECORD = 0x1e;
// ponytail: a row holding one of them is dropped on decode, switch to JSON if that ever happens.
```

주석은 "RSS 텍스트에 제어문자가 올 리 없다"를 전제로 한다. 그런데 `boardSlug`는 링크의 경로에서 오고, `URI.getPath()`는 **퍼센트 인코딩을 디코딩해서 돌려준다.**

```java
String path = uri.getPath() == null ? "" : uri.getPath();
path = path.replaceAll("^/+|/+$", "");
```

따라서 링크가 `https://www.illusionlive.com/ev%1Fil%1Ex?bmode=view` 이면 slug에 0x1F·0x1E가 그대로 들어간다. 재현 결과:

```
parsed = 2
  slug bytes = [101, 118, 31, 105, 108, 30, 120]   ← 0x1F, 0x1E 포함
  slug bytes = [101, 98]
after cache round-trip = 1  (both should survive)
```

**글 2개를 캐시에 넣었는데 1개만 돌아왔다.** 주입된 구분자가 행 경계를 어긋나게 만들어 자기 자신뿐 아니라 **옆 글까지 같이 없앤다.** 주석이 "언젠가 생기면"이라고 미뤄 둔 상황이 이미 원격 입력으로 닿는다는 뜻이다.

영향은 캐시 목록이 조용히 비는 것까지다. `seen_ids`는 별도로 저장되므로 알림 자체가 중복되지는 않는다.

**고칠 방향** — 두 가지 중 하나면 된다.

- `parseItem`에서 slug에 제어문자가 있으면 그 글을 버린다. 저장 포맷을 안 건드리고 검사 한 번으로 끝난다.

  ```java
  for (int i = 0; i < path.length(); i++) if (path.charAt(i) < 0x20) return null;
  ```

- 아니면 주석이 예고한 대로 캐시를 JSON(`org.json`, 이미 플랫폼에 있음)으로 바꾼다.

앞쪽이 더 작다. 자기 검사에 위 `%1F` 링크를 넣어 왕복 후 개수가 유지되는지 고정한다.

## 3. 서명 비밀번호가 명령줄 인자로 노출된다

**심각도: 중** · `build-android.ps1:102`, `build-android.ps1:109-110`

비밀번호를 저장소에서 빼내 환경변수로 옮긴 것은 이전 점검에서 이미 처리됐다. 다만 그 값을 도구에 넘기는 방식이 아직 명령줄이다.

```powershell
& (Join-Path $jdk 'bin\keytool.exe') -genkeypair -noprompt -storetype PKCS12 `
    -keystore $keystore -storepass $password -keypass $password -alias illusionlive `
...
& (Join-Path $tools 'apksigner.bat') sign --ks $keystore --ks-pass "pass:$password" `
    --key-pass "pass:$password" --out $signedApk $alignedApk
```

Windows에서 프로세스의 명령줄은 같은 사용자로 도는 **다른 프로세스가 읽을 수 있다** (`Get-CimInstance Win32_Process | Select CommandLine`). 빌드가 도는 몇 초 동안 그 PC의 임의 프로그램이 서명 키 비밀번호를 그대로 가져갈 수 있다. 키가 새면 이 앱을 사칭한 APK에 같은 서명이 붙고, 사용자 기기에서는 정품 업데이트로 덮어써진다.

**고칠 방향** — `apksigner`는 명령줄 대신 환경변수·파일·표준입력에서 비밀번호를 읽는 형식을 지원한다.

```powershell
& (Join-Path $tools 'apksigner.bat') sign --ks $keystore `
    --ks-pass env:ILLUSIONLIVE_KEYSTORE_PASSWORD `
    --key-pass env:ILLUSIONLIVE_KEYSTORE_PASSWORD `
    --out $signedApk $alignedApk
```

`keytool`에는 대응되는 형식이 없으므로 `-storepass`/`-keypass`를 빼고 표준입력으로 넘긴다. 이 경로는 키스토어가 없을 때 한 번만 타므로 대화형으로 두어도 손해가 없다.

## 4. Windows 쪽에만 응답 크기 제한이 없다

**심각도: 하** · `RssFeedClient.cs:24`

Android는 읽는 도중 상한을 건다.

```java
if (total > MAX_FEED_BYTES) throw new IOException("RSS is larger than 2 MB");
```

Windows에는 같은 방어가 없다.

```csharp
var xml = await response.Content.ReadAsStringAsync(cancellationToken);
```

응답 본문을 통째로 문자열에 담는다. 사이트가 잘못된 응답을 내보내거나 중간자가 응답을 바꾸면 메모리가 그대로 밀린다. 20초 타임아웃이 걸려 있어 무한정은 아니지만, 그 20초 동안 들어온 만큼은 다 받는다.

**고칠 방향** — Android와 같은 2 MB 상한을 건다. `_http.MaxResponseContentBufferSize = 2 * 1024 * 1024;` 한 줄이면 초과 시 예외가 나고, 기존 `catch`가 그대로 받는다.

## 5. Android는 링크를 열 때 스킴을 확인하지 않는다

**심각도: 하 (심층 방어)** · `MainActivity.java:773`

```java
private void openPost(FeedParser.Post post) {
    try {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(post.url)));
```

Windows 쪽 같은 자리에는 검사가 있다.

```csharp
if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) return;
```

`post.url`은 파싱 시점에는 https + illusionlive.com으로 검증되지만, 캐시를 거쳐 돌아올 때는 다시 확인하지 않는다. `FeedParser.decode`가 URL을 그대로 복원하는 것도 재현했다.

```
decoded url = javascript:alert(1)
```

지금은 실제로 악용되지 않는다. 캐시는 `MODE_PRIVATE` SharedPreferences에 있고 `allowBackup="false"`라 adb 백업 복원 경로도 막혀 있다. 즉 이 값을 심으려면 이미 기기를 장악한 상태여야 한다. 그래도 **Windows에 있는 검사가 Android에만 없다는 비대칭**은 남는다.

**고칠 방향** — `openPost`에서 열기 직전에 스킴을 확인하거나, `decode`에서 https가 아닌 행을 버린다. 뒤쪽이 저장된 캐시까지 한 번에 정리해 준다.

## 6. Windows XXE 방어가 프레임워크 기본값에만 기대고 있다

**심각도: 하** · `RssFeedClient.cs:30`

```csharp
var document = XDocument.Parse(xml, LoadOptions.None);
```

.NET Core 이후 `XmlReaderSettings.DtdProcessing` 기본값이 `Prohibit`이라 DOCTYPE가 있으면 예외가 난다. 즉 **현재는 안전하다.** 다만 Android는 이 방어를 코드와 주석과 자기 검사 세 곳에 명시해 둔 반면, Windows는 명시된 데가 없다. 파싱 방식을 바꾸거나 런타임이 달라지면 조용히 사라질 수 있는 종류의 보호다.

**고칠 방향** — 급하지 않다. `SelfTest.cs`에 DOCTYPE가 든 피드가 거부되는지 확인하는 단정을 하나 넣어 기본값을 테스트로 고정해 두면 충분하다.

## 7. 게시판 목록이 줄지 않고 늘기만 한다

**심각도: 하** · `FeedChecker.java:158`, `FeedChecker.java:179`

```java
for (FeedParser.Post post : posts) {
    boards.add(post.boardSlug);
```

피드에 나온 경로는 모두 `dynamic_boards`에 들어가고, 그 뒤로 **제거되지 않는다.** 상한도 없고 slug 길이·문자 제한도 없다. 개별 게시판 스위치도 `board_enabled_<slug>` 라는 키를 하나씩 만든다.

정상 운영에서는 게시판이 수십 개라 문제가 없다. 하지만 피드가 변조되면 설정 화면이 무의미한 행으로 채워지고 SharedPreferences 파일이 계속 커진다. 되돌리려면 앱 데이터를 지우는 수밖에 없다.

**고칠 방향** — slug 길이 상한(예: 64자)과 목록 개수 상한을 걸고, 캐시에 남은 글에 나오지 않는 slug는 저장 시 떨군다. [2번](#2-피드-링크가-캐시-구분자를-주입할-수-있다)의 제어문자 필터와 같은 자리에서 처리하면 코드가 한 군데로 모인다.

## 8. 사이트 사본이 무시 목록에 빠져 있다

**심각도: 하 (위생)** · `.gitignore`

저장소 루트에 `il.html` 420 KB가 추적되지 않은 채 놓여 있다. 사이트 첫 화면을 그대로 받아 둔 사본이다. 훑어봤을 때 자격 증명이나 개인정보는 들어 있지 않았다.

문제는 `.gitignore`가 같은 성격의 파일을 이미 나열해 두었는데 이것만 빠졌다는 점이다.

```
homepage.html
community.html
bb_free.html
chung02.html
newspage.html
```

`git add .` 한 번이면 제3자 사이트 사본이 저장소에 들어간다. 앞으로 받는 사본에 로그인 상태의 페이지가 섞이면 그때는 실제로 새는 것이 된다.

**고칠 방향** — 개별 파일을 계속 늘리는 대신 `/*.html` 로 루트 HTML을 한 번에 무시하고, 나열된 다섯 줄을 지운다.

## 9. Windows 실행 파일에 서명이 없다

**심각도: 정보** · `build.ps1`

Android 쪽은 릴리스마다 APK의 SHA-256과 서명 인증서 지문을 공개하고 있어서 사용자가 받은 파일을 검증할 수 있다. Windows `dist/IllusionLiveNotifier.exe`에는 Authenticode 서명이 없고 해시 공개도 없다. 사용자는 SmartScreen 경고를 그대로 지나칠 수밖에 없고, 받은 파일이 원본인지 확인할 방법이 없다.

**고칠 방향** — 코드 서명 인증서는 유료라 이 규모에는 과하다. 대신 `build.ps1` 마지막에 `Get-FileHash`를 붙여 zip의 SHA-256을 출력하고, `build-android.ps1`이 하듯 릴리스 노트에 적는다.

## 10. OpenUrl이 http도 받는다

**심각도: 정보** · `MainForm.cs:714`

```csharp
if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) return;
```

파서는 https만 통과시키므로 평문 http URL이 여기까지 오는 경로는 손으로 고친 `settings.json` 뿐이다. 실질 위험은 없지만 검사 두 개가 서로 다를 이유도 없다. `"https"` 하나로 좁히면 된다.

---

## 이미 막혀 있는 것

되짚을 필요 없게 확인된 것만 적는다.

- **저장소에 비밀 없음** — 모든 ref에 대해 `git log --diff-filter=A`로 추가된 파일 전체와 `git grep`으로 비밀번호성 문자열을 훑었다. 키스토어·인증서·평문 비밀번호가 커밋된 이력이 없다. `build-android.ps1`의 `-storepass $password`는 변수이고 값이 저장소에 없다.
- **평문 통신 차단** — `android:usesCleartextTraffic="false"`, 양쪽 파서 모두 https 강제.
- **백업 차단** — `android:allowBackup="false"` 라 adb 백업으로 앱 데이터를 빼거나 심을 수 없다.
- **리시버 비공개** — `FeedAlarmReceiver`가 `exported="false"`. BOOT_COMPLETED는 여전히 도착하지만 다른 앱이 CHECK 액션을 던져 피드를 긁게 만들 수 없다.
- **링크 출처 검증** — 두 파서 모두 스킴 https + 호스트 `illusionlive.com`/`www.illusionlive.com` + `bmode=view` 를 요구한다. 변조된 피드가 외부 링크를 목록에 밀어 넣지 못한다.
- **XXE** — Android는 DOCTYPE 사전 스캔(UTF-8/UTF-16 모두 자기 검사로 고정), Windows는 `XDocument.Parse` 기본값. ([6번](#6-windows-xxe-방어가-프레임워크-기본값에만-기대고-있다)은 명시성 문제일 뿐 현재 동작은 안전하다.)
- **응답 크기** — Android 2 MB 상한. ([4번](#4-windows-쪽에만-응답-크기-제한이-없다)은 Windows 쪽 누락.)
- **PendingIntent** — `FLAG_IMMUTABLE` 사용. 다른 앱이 인텐트 내용을 바꿔 재사용할 수 없다.
- **자동 시작 레지스트리** — `key.SetValue(name, $"\"{path}\" --background")` 로 경로를 따옴표로 감쌌다. 공백 경로 하이재킹이 안 된다.
- **빌드 출력 경로** — `Reset-SafeDirectory` / `distFull.StartsWith(rootFull)` 로 `Remove-Item -Recurse -Force` 가 작업 폴더 밖을 지우지 못하게 막았다.
- **저장소** — SharedPreferences `MODE_PRIVATE`, Windows는 `LocalApplicationData` 아래. 피드 내용을 로그로 남기지 않는다.
- **권한** — INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 다섯 개뿐. 위치·연락처·저장소 권한 없음.
- **캐시 상한** — 글 200개, seen id 500개로 양쪽 모두 제한.

## 권장 순서

1. **[1번](#1-피드-글-하나가-전체-알림을-멈출-수-있다)** — 유일하게 공격자 없이도 터지는 항목이다. 사이트에 그런 제목의 글이 올라오면 전 사용자 알림이 멈춘다.
2. **[3번](#3-서명-비밀번호가-명령줄-인자로-노출된다)** — 영향이 가장 크고(키 유출 = 앱 사칭) 고치는 데 한 줄이다.
3. **[2번](#2-피드-링크가-캐시-구분자를-주입할-수-있다) + [7번](#7-게시판-목록이-줄지-않고-늘기만-한다)** — 둘 다 `parseItem`의 slug 검증 한 곳에서 처리된다.
4. **[4번](#4-windows-쪽에만-응답-크기-제한이-없다) + [5번](#5-android는-링크를-열-때-스킴을-확인하지-않는다) + [10번](#10-openurl이-http도-받는다)** — 두 앱 사이에 벌어진 방어를 맞추는 작업. 각각 한두 줄.
5. **[6번](#6-windows-xxe-방어가-프레임워크-기본값에만-기대고-있다) · [8번](#8-사이트-사본이-무시-목록에-빠져-있다) · [9번](#9-windows-실행-파일에-서명이-없다)** — 위생 항목. 시간 날 때.

1·2·5번은 실제로 재현해 확인했다. 재현에 쓴 코드는 이 저장소에 남기지 않았고, 고칠 때 각 항목의 "고칠 방향"에 적은 단정을 `SelfTest`에 넣어 회귀를 막는 편이 낫다.

## 점검 범위 밖

- **사이트(`illusionlive.com`) 자체** — 이 앱은 RSS를 읽기만 한다. 서버 보안은 별개다.
- **런타임 동적 분석** — 정적 분석과 `FeedParser` 단위 재현까지만 했다. 실기기에서 트래픽을 가로채는 시험은 하지 않았다.
- **Windows 빌드 검증** — 이 PC에 .NET SDK가 없어 `build.ps1`을 돌리지 못했다. C# 항목([4](#4-windows-쪽에만-응답-크기-제한이-없다)·[6](#6-windows-xxe-방어가-프레임워크-기본값에만-기대고-있다)·[10](#10-openurl이-http도-받는다)번)은 코드 검토 결과이고 실행으로 확인하지 않았다.
