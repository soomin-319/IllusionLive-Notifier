# 보안 점검 보고서 (Android)

- 대상: `IllusionLiveNotifier.Android` (Java), `build-android.ps1`
- 기준 커밋: `f7c41fc` (main)
- 점검일: 2026-09-02
- 이전 점검(`23d8110`, PR #16)에서 고친 항목은 [이미 막혀 있는 것](#이미-막혀-있는-것)에 정리했고, 이 문서는 **아직 남아 있는 것**만 다룬다.
- 같은 저장소의 Windows 앱은 이 문서 범위 밖이다.

앱은 서버·계정·추적이 없고 외부에서 들어오는 데이터가 RSS 피드 하나뿐이다. 그래서 위험은 대부분 "피드 내용이 앱을 어디까지 흔들 수 있는가"에 몰려 있다. 원격 코드 실행이나 데이터 유출로 이어지는 결함은 발견되지 않았다.

## 요약

| # | 심각도 | 위치 | 문제 | 확인 |
|---|--------|------|------|------|
| [1](#1-피드-글-하나가-전체-알림을-멈출-수-있다) | 중 | `FeedParser.java:56,178` | CDATA 안의 `<!DOCTYPE` 문자열이 피드 전체 파싱을 중단시킨다 | 재현됨 |
| [2](#2-피드-링크가-캐시-구분자를-주입할-수-있다) | 중 | `FeedParser.java:123,88,99` | 링크 경로의 `%1F`/`%1E`가 캐시 구분자와 충돌해 다른 글까지 사라진다 | 재현됨 |
| [3](#3-캐시에서-꺼낸-링크를-검증-없이-연다) | 하 | `MainActivity.java:773`, `FeedParser.java:99` | 파서가 건 https·호스트 검증이 캐시를 거치면 사라진다 | 재현됨 |
| [4](#4-게시판-목록이-줄지-않고-늘기만-한다) | 하 | `FeedChecker.java:158,179` | `dynamic_boards`와 `board_enabled_*` 키가 정리되지 않는다 | 코드 확인 |

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

## 3. 캐시에서 꺼낸 링크를 검증 없이 연다

**심각도: 하 (심층 방어)** · `MainActivity.java:773`, `FeedParser.java:99`

`parseItem`은 링크를 받을 때 https + `illusionlive.com` 호스트 + `bmode=view`를 요구한다. 그런데 그 링크가 캐시에 저장됐다 돌아올 때는 아무것도 다시 확인하지 않는다.

```java
private void openPost(FeedParser.Post post) {
    try {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(post.url)));
```

`FeedParser.decode`가 저장된 문자열을 그대로 URL로 복원하는 것도 재현했다.

```
decoded url = javascript:alert(1)
```

즉 파서가 건 보증이 캐시 경계에서 끊긴다. **지금은 실제로 악용되지 않는다.** 캐시는 `MODE_PRIVATE` SharedPreferences에 있고 `allowBackup="false"` 라 adb 백업 복원 경로도 막혀 있어서, 이 값을 심으려면 이미 기기를 장악한 상태여야 한다. 하지만 "여기 오는 값은 이미 검증됐다"는 전제가 코드 어디에도 적혀 있지 않고, 저장 포맷이나 캐시 채우는 경로가 하나만 늘어도 조용히 깨진다.

**고칠 방향** — `decode`에서 https가 아닌 행을 버린다. 열기 직전에 막는 것보다 낫다. 이미 저장돼 있는 캐시까지 한 번에 정리되고, 캐시를 읽는 자리가 앞으로 늘어도 검사가 따라간다.

## 4. 게시판 목록이 줄지 않고 늘기만 한다

**심각도: 하** · `FeedChecker.java:158`, `FeedChecker.java:179`

```java
for (FeedParser.Post post : posts) {
    boards.add(post.boardSlug);
```

피드에 나온 경로는 모두 `dynamic_boards`에 들어가고, 그 뒤로 **제거되지 않는다.** 상한도 없고 slug 길이·문자 제한도 없다. 개별 게시판 스위치도 `board_enabled_<slug>` 라는 키를 하나씩 만든다.

정상 운영에서는 게시판이 수십 개라 문제가 없다. 하지만 피드가 변조되면 설정 화면이 무의미한 행으로 채워지고 SharedPreferences 파일이 계속 커진다. 되돌리려면 앱 데이터를 지우는 수밖에 없다.

**고칠 방향** — slug 길이 상한(예: 64자)과 목록 개수 상한을 걸고, 캐시에 남은 글에 나오지 않는 slug는 저장 시 떨군다. [2번](#2-피드-링크가-캐시-구분자를-주입할-수-있다)의 제어문자 필터와 같은 자리에서 처리하면 코드가 한 군데로 모인다.

---

## 이미 막혀 있는 것

되짚을 필요 없게 확인된 것만 적는다.

- **저장소에 비밀 없음** — 모든 ref에 대해 `git log --diff-filter=A`로 추가된 파일 전체와 `git grep`으로 비밀번호성 문자열을 훑었다. 키스토어·인증서·평문 비밀번호가 커밋된 이력이 없다. `build-android.ps1`의 `-storepass $password`는 변수이고 값이 저장소에 없다.
- **평문 통신 차단** — `android:usesCleartextTraffic="false"`, 파서가 https 강제.
- **백업 차단** — `android:allowBackup="false"` 라 adb 백업으로 앱 데이터를 빼거나 심을 수 없다.
- **리시버 비공개** — `FeedAlarmReceiver`가 `exported="false"`. BOOT_COMPLETED는 여전히 도착하지만 다른 앱이 CHECK 액션을 던져 피드를 긁게 만들 수 없다.
- **링크 출처 검증** — `parseItem`이 스킴 https + 호스트 `illusionlive.com`/`www.illusionlive.com` + `bmode=view` 를 요구한다. 변조된 피드가 외부 링크를 목록에 밀어 넣지 못한다. ([3번](#3-캐시에서-꺼낸-링크를-검증-없이-연다)은 이 검증이 캐시를 못 넘는다는 별개 문제다.)
- **XXE** — DOCTYPE 사전 스캔이 UTF-8·UTF-16LE·UTF-16BE 표기를 모두 잡고, 자기 검사가 이를 고정하고 있다. Android의 `DocumentBuilderFactory`가 `disallow-doctype-decl` 계열 기능 이름을 전부 거부하므로 기기에서 실제로 막는 것은 이 스캔이다. ([1번](#1-피드-글-하나가-전체-알림을-멈출-수-있다)은 이 스캔이 **너무 많이** 잡는 문제이지 못 잡는 문제가 아니다.)
- **응답 크기** — `MAX_FEED_BYTES` 2 MB 상한을 읽는 도중에 건다.
- **PendingIntent** — `FLAG_IMMUTABLE` 사용. 다른 앱이 인텐트 내용을 바꿔 재사용할 수 없다.
- **빌드 출력 경로** — `Reset-SafeDirectory` 가 `Remove-Item -Recurse -Force` 대상이 지정한 상위 폴더 안인지 확인한다. 작업 폴더 밖을 지우지 못한다.
- **저장소** — SharedPreferences `MODE_PRIVATE`. 피드 내용을 로그로 남기지 않는다.
- **권한** — INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, RECEIVE_BOOT_COMPLETED, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 다섯 개뿐. 위치·연락처·저장소 권한 없음.
- **캐시 상한** — 글 200개, seen id 500개.
- **배포 검증 수단** — 릴리스마다 APK의 SHA-256과 서명 인증서 지문을 공개하고 있어 사용자가 받은 파일을 확인할 수 있다.

## 권장 순서

1. **[1번](#1-피드-글-하나가-전체-알림을-멈출-수-있다)** — 유일하게 공격자 없이도 터지는 항목이다. 사이트에 그런 제목의 글이 올라오면 전 사용자 알림이 멈춘다.
2. **[2번](#2-피드-링크가-캐시-구분자를-주입할-수-있다) + [4번](#4-게시판-목록이-줄지-않고-늘기만-한다)** — 둘 다 `parseItem`의 slug 검증 한 곳에서 처리된다.
3. **[3번](#3-캐시에서-꺼낸-링크를-검증-없이-연다)** — `decode`에 https 검사 한 줄.

1·2·3번은 실제로 재현해 확인했다. 재현에 쓴 코드는 이 저장소에 남기지 않았고, 고칠 때 각 항목의 "고칠 방향"에 적은 단정을 `SelfTest`에 넣어 회귀를 막는 편이 낫다.

## 점검 범위 밖

- **Windows 앱** — 같은 저장소의 `IllusionLiveNotifier/` 는 이번 문서에서 다루지 않는다.
- **사이트(`illusionlive.com`) 자체** — 이 앱은 RSS를 읽기만 한다. 서버 보안은 별개다.
- **런타임 동적 분석** — 정적 분석과 `FeedParser` 단위 재현까지만 했다. 실기기에서 트래픽을 가로채는 시험은 하지 않았다.
