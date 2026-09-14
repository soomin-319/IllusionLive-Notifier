# 댓글 알림 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 내가 쓴 글에 댓글이 달리거나 내가 쓴 댓글에 누가 답하면 Android 앱이 알림을 보낸다.

**Architecture:** 기존 5분 RSS 폴링 사이클 뒤에 댓글 확인 단계를 붙인다. 순수 자바 클래스 3개(`CommentParser`·`TrackedPosts`·`CommentRules`)가 파싱·저장·판단을 맡아 `SelfTest`로 전부 검증되고, Android API를 쓰는 `CommentChecker` 하나가 그 셋을 묶어 네트워크·설정·알림을 처리한다.

**Tech Stack:** Java 8 문법(`--release 8`), Android SDK 36 / minSdk 26, `HttpURLConnection`, `SharedPreferences`, `java.util.regex`. 외부 라이브러리 없음.

**Spec:** `docs/superpowers/specs/2026-09-12-comment-notifications-design.md`

## Global Constraints

- **테스트 대상 클래스는 Android API를 쓸 수 없다.** `build-android.ps1`은 `android.jar` 없이 `FeedParser`·`MemberColors`·`SelfTest`만 컴파일해 자체 테스트를 돌린다. `CommentParser`·`TrackedPosts`·`CommentRules`도 여기에 합류하므로 `android.*` import가 하나라도 있으면 빌드가 깨진다.
- **Java 8 문법만.** `--release 8`. `var`, `List.of`, `Map.of`, 텍스트 블록 금지. `Arrays.asList` 사용.
- **경고가 곧 실패.** `-Xlint:all -Werror`. 원시 타입, 미검사 캐스트, 미사용 import 전부 빌드 중단.
- **모든 문자열은 한국어.** 알림 문구, 설정 라벨, 안내 문구.
- **저장 구분자.** `FeedParser`가 쓰는 `0x1f`(필드)/`0x1e`(레코드)를 그대로 쓴다.
- **코드 형식 검증.** 사이트에서 온 식별자는 `^[cpbm][0-9a-f]{1,40}$`를 통과한 것만 저장한다.
- **네트워크 상한.** 사이클당 발견 스캔 3글, 추적 재확인 8글.
- **보관.** 추적 글 상한 20개, 3일 만료.
- **버전.** 작업 종료 시 `build-android.ps1`의 `--version-code 13 → 14`, `--version-name 1.0.12 → 1.1.0`.
- **브랜치.** 이미 `feat/comment-notifications`에서 작업 중이다. `main`에 직접 커밋하지 않는다.

### 테스트 명령

각 순수 클래스 작업의 테스트 단계는 이 명령으로 돈다. Task 1에서 `test-android.ps1`로 저장하므로 이후에는 `./test-android.ps1` 한 줄이다.

```powershell
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    (Get-ChildItem 'C:\Android\illusion-tools\jdk' -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1).FullName
}
```

## 파일 구성

| 파일 | 상태 | 책임 |
|---|---|---|
| `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentParser.java` | 신규 | 순수. 댓글 HTML → 댓글 목록, 글 페이지 HTML → `post_code`/`board_code` |
| `.../TrackedPosts.java` | 신규 | 순수. 추적 레코드 인코딩, 만료·상한, 확인 순서 선택 |
| `.../CommentRules.java` | 신규 | 순수. 새 댓글 중 무엇을 알릴지 판단 |
| `.../CommentChecker.java` | 신규 | Android. 설정 키, HTTP, 사이클 오케스트레이션, 알림 |
| `.../Tutorial.java` | 신규 | Android. `MainActivity`에서 첫 실행 안내를 분리하고 닉네임 페이지를 추가 |
| `.../FeedChecker.java` | 수정 | RSS 처리 뒤 `CommentChecker.run` 호출 |
| `.../MainActivity.java` | 수정 | 안내 코드 제거, 설정 카드와 닉네임 다이얼로그 추가, UI 헬퍼 10개를 package-private로 |
| `tests/com/illusionlive/notifier/SelfTest.java` | 수정 | 순수 클래스 3개 검증 추가 |
| `test-android.ps1` | 신규 | 순수 클래스만 컴파일해 `SelfTest`를 돌리는 개발용 스크립트 |
| `build-android.ps1` | 수정 | 자체 테스트 컴파일 목록에 새 순수 클래스 3개 추가, 버전 상향 |
| `README.md` | 수정 | 기능·데이터 항목 갱신 |

---

### Task 1: CommentParser — 댓글 목록 파싱

**Files:**
- Create: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentParser.java`
- Create: `test-android.ps1`
- Modify: `build-android.ps1:53`
- Test: `IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `CommentParser.Comment` — 필드 `String code`, `String author`, `String member`, `String body` (모두 final, package-private)
  - `static List<CommentParser.Comment> CommentParser.parseComments(String html)` — 화면 순서 그대로. 코드 형식이 틀린 댓글은 버린다

- [ ] **Step 1: 테스트 스크립트 만들기**

`test-android.ps1` (저장소 루트):

```powershell
# 순수 자바 클래스만 모아 SelfTest 를 돌린다. APK 를 만들지 않으므로 키스토어가 필요 없다.
# 컴파일 목록은 build-android.ps1 의 자체 테스트 줄과 같아야 한다. 한쪽만 고치지 말 것.
$ErrorActionPreference = 'Stop'
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else {
    (Get-ChildItem 'C:\Android\illusion-tools\jdk' -Directory |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1).FullName
}
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) { throw 'JDK 17 not found.' }

$src = Join-Path $PSScriptRoot 'IllusionLiveNotifier.Android\src\com\illusionlive\notifier'
$tests = Join-Path $PSScriptRoot 'IllusionLiveNotifier.Android\tests\com\illusionlive\notifier'
$out = Join-Path $env:TEMP 'illusionlive-test-classes'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

$sources = @(
    (Join-Path $src 'FeedParser.java'),
    (Join-Path $src 'MemberColors.java'),
    (Join-Path $src 'CommentParser.java'),
    (Join-Path $tests 'SelfTest.java')
)
& (Join-Path $jdk 'bin\javac.exe') --release 8 -encoding UTF-8 -Xlint:all -Werror -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "compile failed (exit $LASTEXITCODE)" }
& (Join-Path $jdk 'bin\java.exe') -ea -cp $out com.illusionlive.notifier.SelfTest
if ($LASTEXITCODE -ne 0) { throw "self-test failed (exit $LASTEXITCODE)" }
```

- [ ] **Step 2: 실패하는 테스트 작성**

`SelfTest.java`의 `main` 끝, `System.out.println("SELF-TEST PASS")` 바로 앞에 넣는다. 픽스처는 `/134/?idx=172966140` 실제 응답을 줄인 것이다.

```java
        // ------------------------------------------------------------ 댓글 파싱
        String commentHtml =
                "<div class=\"comment\" id=\"c202608090b81597ef232d\">" +
                "<div class=\" main_comment _comment_wrap _comment_wrap_c202608090b81597ef232d\">" +
                "<div class=\"write\">위즐리어카<span class=\"_comment_at_nick tag date\">2026-08-09 01:32</span></div>" +
                "<span class=\"_comment_body_m20260818c51a24881609d  _comment_body_c202608090b81597ef232d\"" +
                " comment_body_code=\"c202608090b81597ef232d\">\n밑에 영상 링크가<br />\r\n잘못된 것 같아염\n</span>" +
                "</div></div>" +
                "<div class=\"comment\" id=\"c20260809dbcf697bb6e08\">" +
                "<div class=\" main_comment _comment_wrap _comment_wrap_c20260809dbcf697bb6e08\">" +
                "<div class=\"write\">유메루<span class=\"_comment_at_nick tag date\">2026-08-09 01:35</span></div>" +
                "<span class=\"_comment_body_m2026020341fbaa000ea22  _comment_body_c20260809dbcf697bb6e08\"" +
                " comment_body_code=\"c20260809dbcf697bb6e08\">이거 맞아염 ヽ(*。&gt;Д&lt;)o゜</span>" +
                "</div></div>";

        List<CommentParser.Comment> comments = CommentParser.parseComments(commentHtml);
        assert comments.size() == 2 : "댓글 두 개가 순서대로 나온다";
        assert "c202608090b81597ef232d".equals(comments.get(0).code);
        assert "위즐리어카".equals(comments.get(0).author);
        assert "m20260818c51a24881609d".equals(comments.get(0).member);
        assert "밑에 영상 링크가 잘못된 것 같아염".equals(comments.get(0).body)
                : "<br> 는 공백이 되고 앞뒤 공백은 사라진다: [" + comments.get(0).body + "]";
        assert "유메루".equals(comments.get(1).author);
        assert "이거 맞아염 ヽ(*。>Д<)o゜".equals(comments.get(1).body)
                : "HTML 엔티티가 풀린다: [" + comments.get(1).body + "]";

        // 코드 형식이 맞지 않으면 그 댓글만 버린다.
        String badCode = commentHtml.replace("comment_body_code=\"c202608090b81597ef232d\"",
                "comment_body_code=\"c../../etc\"");
        assert CommentParser.parseComments(badCode).size() == 1 : "형식이 틀린 코드는 버린다";

        // 작성자와 본문이 비어도 파싱 자체는 무너지지 않는다.
        assert CommentParser.parseComments("").isEmpty() : "빈 문자열은 빈 목록";
        assert CommentParser.parseComments("<div class=\"comment\" id=\"cZZZ\"></div>").isEmpty()
                : "본문 없는 껍데기는 버린다";
```

- [ ] **Step 3: 테스트가 실패하는지 확인**

Run: `./test-android.ps1`
Expected: 컴파일 실패. `cannot find symbol ... class CommentParser`

- [ ] **Step 4: CommentParser 구현**

```java
package com.illusionlive.notifier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 아임웹 댓글 응답을 읽는다. {@code /ajax/post_comment_paging.cm} 이 돌려주는 JSON 의 html 필드가
 * 입력이고, 글 페이지 HTML 에서 그 호출에 필요한 두 코드를 뽑는 일도 여기서 한다.
 *
 * <p>Android API 를 쓰지 않는다. 이 클래스는 {@code SelfTest} 가 android.jar 없이 컴파일한다.
 *
 * <p>정규식으로 읽는다. 사이트가 마크업을 바꾸면 빈 목록이 나오고 알림이 조용히 멈춘다. 문서를
 * 파싱해도 같은 위험이 남고, 이 응답에는 태그 구조보다 클래스 이름이 더 안정적이다.
 */
final class CommentParser {
    /** 댓글·글·게시판·회원 코드의 공통 형태. 저장 구분자가 섞여 들어올 길을 막는다. */
    private static final Pattern CODE = Pattern.compile("^[cpbm][0-9a-f]{1,40}$");
    private static final Pattern BLOCK =
            Pattern.compile("<div class=\"comment\" id=\"[^\"]*\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR =
            Pattern.compile("<div class=\"write\">(.*?)<span", Pattern.DOTALL);
    private static final Pattern MEMBER = Pattern.compile("_comment_body_(m[0-9a-f]+)");
    private static final Pattern BODY = Pattern.compile(
            "comment_body_code=\"([^\"]*)\"\\s*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    /** 알림 한 줄에 들어갈 만큼만 남긴다. */
    private static final int MAX_BODY = 120;

    private CommentParser() {}

    static final class Comment {
        final String code;
        final String author;
        final String member;
        final String body;

        Comment(String code, String author, String member, String body) {
            this.code = code;
            this.author = author;
            this.member = member;
            this.body = body;
        }
    }

    /** 화면에 보이는 순서 그대로. 코드가 형식에 맞지 않는 댓글은 버린다. */
    static List<Comment> parseComments(String html) {
        List<Comment> comments = new ArrayList<>();
        if (html == null || html.isEmpty()) return comments;

        Matcher block = BLOCK.matcher(html);
        List<Integer> starts = new ArrayList<>();
        while (block.find()) starts.add(block.start());

        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            Comment comment = parseBlock(html.substring(from, to));
            if (comment != null) comments.add(comment);
        }
        return comments;
    }

    private static Comment parseBlock(String block) {
        Matcher body = BODY.matcher(block);
        if (!body.find()) return null;
        String code = body.group(1);
        if (!CODE.matcher(code).matches()) return null;

        Matcher author = AUTHOR.matcher(block);
        String nick = author.find() ? clean(strip(author.group(1)), 60) : "";

        Matcher member = MEMBER.matcher(block);
        String memberCode = member.find() && CODE.matcher(member.group(1)).matches()
                ? member.group(1) : "";

        return new Comment(code, nick, memberCode, clean(strip(body.group(2)), MAX_BODY));
    }

    /** 태그를 공백으로 바꾼다. {@code <br>} 로 나뉜 줄이 붙어 버리지 않게 지우지 않고 바꾼다. */
    private static String strip(String html) {
        return TAG.matcher(html).replaceAll(" ");
    }

    /** 엔티티를 풀고, 제어문자와 연속 공백을 한 칸으로 줄이고, 길이를 자른다. */
    private static String clean(String text, int max) {
        String value = text
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        value = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
```

`&amp;`를 마지막에 푸는 순서가 중요하다. 먼저 풀면 `&amp;lt;`가 `<`로 두 번 풀린다.

- [ ] **Step 5: 컴파일 목록에 추가**

`test-android.ps1`의 `$sources`에 이미 `CommentParser.java`가 들어 있다. `build-android.ps1` 53번 줄 위쪽에 변수를 하나 더 만들고 컴파일 줄에 넣는다.

```powershell
$parser = Join-Path $input.FullName 'src\com\illusionlive\notifier\FeedParser.java'
$colors = Join-Path $input.FullName 'src\com\illusionlive\notifier\MemberColors.java'
$commentParser = Join-Path $input.FullName 'src\com\illusionlive\notifier\CommentParser.java'
$selfTest = Join-Path $input.FullName 'tests\com\illusionlive\notifier\SelfTest.java'

& $javac --release 8 -encoding UTF-8 -Xlint:all -Werror -d $testClasses.FullName $parser $colors $commentParser $selfTest
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./test-android.ps1`
Expected: `SELF-TEST PASS`

- [ ] **Step 7: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentParser.java \
        IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java \
        test-android.ps1 build-android.ps1
git commit -m "feat: parse imweb comment list HTML"
```

---

### Task 2: CommentParser — 글 페이지에서 두 코드 뽑기

**Files:**
- Modify: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentParser.java`
- Test: `IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java`

**Interfaces:**
- Consumes: Task 1의 `CommentParser`
- Produces:
  - `static String CommentParser.postCode(String pageHtml)` — 없거나 형식이 틀리면 `""`
  - `static String CommentParser.boardCode(String pageHtml)` — 없거나 형식이 틀리면 `""`

- [ ] **Step 1: 실패하는 테스트 작성**

Task 1이 넣은 블록 바로 뒤에 이어 쓴다.

```java
        // --------------------------------------------- 글 페이지에서 두 코드 뽑기
        String page = "<form id=\"comment_form\">" +
                "<input type=\"hidden\" name=\"post_code\" value=\"p20260809213e43a4f8ac7\">" +
                "<input type=\"hidden\" name=\"board_code\" value=\"b20260808bcd2709bb86e5\">" +
                "<input type=\"hidden\" name=\"comment_token\" value=\"uKkIdNEaSBBahdwJ+2otkk==\">" +
                "</form>";
        assert "p20260809213e43a4f8ac7".equals(CommentParser.postCode(page));
        assert "b20260808bcd2709bb86e5".equals(CommentParser.boardCode(page));
        assert CommentParser.postCode("<html></html>").isEmpty() : "없으면 빈 문자열";
        assert CommentParser.postCode(
                "<input name=\"post_code\" value=\"p20260809../../x\">").isEmpty()
                : "형식이 틀리면 빈 문자열";
        assert CommentParser.boardCode(
                "<input name=\"board_code\" value=\"p20260809213e43a4f8ac7\">").isEmpty()
                : "접두 문자가 다르면 board_code 가 아니다";
```

- [ ] **Step 2: 테스트가 실패하는지 확인**

Run: `./test-android.ps1`
Expected: 컴파일 실패. `cannot find symbol: method postCode(String)`

- [ ] **Step 3: 구현 추가**

`CommentParser`에 넣는다. 상수는 기존 상수들 옆에.

```java
    private static final Pattern POST_CODE = hidden("post_code");
    private static final Pattern BOARD_CODE = hidden("board_code");

    private static Pattern hidden(String name) {
        return Pattern.compile("name=\"" + name + "\"\\s+value=\"([^\"]*)\"");
    }
```

메서드는 `parseComments` 아래에.

```java
    /** 글 페이지의 댓글 폼에 박혀 있는 글 코드. 없거나 형식이 틀리면 빈 문자열. */
    static String postCode(String pageHtml) { return code(pageHtml, POST_CODE, 'p'); }

    /** 같은 폼의 게시판 코드. 댓글 조회에 두 값이 모두 필요하다. */
    static String boardCode(String pageHtml) { return code(pageHtml, BOARD_CODE, 'b'); }

    private static String code(String pageHtml, Pattern pattern, char prefix) {
        if (pageHtml == null) return "";
        Matcher matcher = pattern.matcher(pageHtml);
        if (!matcher.find()) return "";
        String value = matcher.group(1);
        if (value.isEmpty() || value.charAt(0) != prefix) return "";
        return CODE.matcher(value).matches() ? value : "";
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./test-android.ps1`
Expected: `SELF-TEST PASS`

- [ ] **Step 5: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentParser.java \
        IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java
git commit -m "feat: read post_code and board_code from a post page"
```

---

### Task 3: TrackedPosts — 레코드, 만료, 상한, 확인 순서

**Files:**
- Create: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/TrackedPosts.java`
- Modify: `test-android.ps1`, `build-android.ps1`
- Test: `IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `TrackedPosts.Tracked` — 필드 `String url`, `String postCode`, `String boardCode`, `long added`, `long checked`, `int reason`
  - `static final int TrackedPosts.MY_POST = 1`, `static final int TrackedPosts.MY_COMMENT = 2` (비트 플래그, 합쳐서 3)
  - `static final int TrackedPosts.MAX = 20`, `static final long TrackedPosts.TTL_MS`
  - `static List<Tracked> decode(String)` / `static String encode(List<Tracked>)`
  - `static List<Tracked> add(List<Tracked> list, String url, String postCode, String boardCode, int reason, long now)`
  - `static List<Tracked> due(List<Tracked> list, int limit)`
  - `static List<Tracked> markChecked(List<Tracked> list, String url, long now)`

- [ ] **Step 1: 실패하는 테스트 작성**

`SelfTest.java`에 이어 쓴다.

```java
        // ---------------------------------------------------------- 추적 목록
        long now = 1_760_000_000_000L;
        long day = 24L * 60 * 60 * 1000;

        List<TrackedPosts.Tracked> tracked = new ArrayList<>();
        tracked = TrackedPosts.add(tracked, "https://www.illusionlive.com/eb?idx=1",
                "p2026090300000000000a1", "b2026090300000000000b1", TrackedPosts.MY_POST, now);
        assert tracked.size() == 1;
        assert tracked.get(0).reason == TrackedPosts.MY_POST;

        // 같은 글이 다시 들어오면 늘지 않고 사유만 합쳐진다.
        tracked = TrackedPosts.add(tracked, "https://www.illusionlive.com/eb?idx=1",
                "p2026090300000000000a1", "b2026090300000000000b1", TrackedPosts.MY_COMMENT, now);
        assert tracked.size() == 1 : "같은 url 은 한 줄";
        assert tracked.get(0).reason == (TrackedPosts.MY_POST | TrackedPosts.MY_COMMENT);

        // 3일이 지난 항목은 다음 add 에서 사라진다.
        List<TrackedPosts.Tracked> aged = TrackedPosts.add(tracked,
                "https://www.illusionlive.com/eb?idx=2",
                "p2026090300000000000a2", "b2026090300000000000b1",
                TrackedPosts.MY_POST, now + 4 * day);
        assert aged.size() == 1 : "3일 지난 첫 글은 빠진다";
        assert aged.get(0).url.endsWith("idx=2");

        // 상한 20개. 21번째가 들어오면 가장 오래 전에 등록된 것이 빠진다.
        List<TrackedPosts.Tracked> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many = TrackedPosts.add(many, "https://www.illusionlive.com/eb?idx=" + i,
                    "p2026090300000000000a1", "b2026090300000000000b1",
                    TrackedPosts.MY_POST, now + i);
        }
        assert many.size() == TrackedPosts.MAX : "상한 " + TrackedPosts.MAX + ", 실제 " + many.size();
        assert many.get(0).url.endsWith("idx=24") : "가장 최근 등록이 앞";

        // 확인 순서: 확인한 지 오래된 것부터, 최대 limit 개.
        List<TrackedPosts.Tracked> queue = TrackedPosts.due(many, 8);
        assert queue.size() == 8;
        List<TrackedPosts.Tracked> after = TrackedPosts.markChecked(many, queue.get(0).url, now + 100);
        assert TrackedPosts.due(after, 1).get(0).url.equals(queue.get(1).url)
                : "방금 확인한 글은 뒤로 밀린다";

        // 왕복 인코딩.
        String encoded = TrackedPosts.encode(many);
        List<TrackedPosts.Tracked> decoded = TrackedPosts.decode(encoded);
        assert decoded.size() == many.size();
        assert decoded.get(0).url.equals(many.get(0).url);
        assert decoded.get(0).boardCode.equals(many.get(0).boardCode);
        assert decoded.get(0).reason == many.get(0).reason;
        assert TrackedPosts.decode("").isEmpty();
        assert TrackedPosts.decode("깨진줄").isEmpty() : "필드 수가 안 맞으면 그 줄은 버린다";

        // 형식이 틀린 코드는 애초에 들어가지 않는다.
        List<TrackedPosts.Tracked> rejected = TrackedPosts.add(new ArrayList<TrackedPosts.Tracked>(),
                "https://www.illusionlive.com/eb?idx=3", "p../../x", "b2026090300000000000b1",
                TrackedPosts.MY_POST, now);
        assert rejected.isEmpty() : "형식이 틀린 post_code 는 거부";

        // http 링크는 저장하지 않는다. 알림을 눌렀을 때 여는 주소이기 때문이다.
        List<TrackedPosts.Tracked> insecure = TrackedPosts.add(new ArrayList<TrackedPosts.Tracked>(),
                "http://www.illusionlive.com/eb?idx=4", "p2026090300000000000a1",
                "b2026090300000000000b1", TrackedPosts.MY_POST, now);
        assert insecure.isEmpty() : "https 가 아니면 거부";
```

`SelfTest.java` 위쪽 import에 `java.util.ArrayList`가 없으면 추가한다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

먼저 `test-android.ps1`의 `$sources` 배열에 `TrackedPosts.java` 줄을 넣고, `build-android.ps1`에도 같은 변수를 추가한다.

```powershell
    (Join-Path $src 'TrackedPosts.java'),
```

```powershell
$trackedPosts = Join-Path $input.FullName 'src\com\illusionlive\notifier\TrackedPosts.java'
& $javac --release 8 -encoding UTF-8 -Xlint:all -Werror -d $testClasses.FullName $parser $colors $commentParser $trackedPosts $selfTest
```

Run: `./test-android.ps1`
Expected: 컴파일 실패. `cannot find symbol ... class TrackedPosts`

- [ ] **Step 3: 구현**

```java
package com.illusionlive.notifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 댓글을 지켜볼 글 목록. 한 글은 내가 썼거나({@link #MY_POST}) 내가 댓글을 달았거나
 * ({@link #MY_COMMENT}) 둘 다여서 들어온다.
 *
 * <p>Android API 를 쓰지 않는다. {@code SelfTest} 가 android.jar 없이 컴파일한다.
 *
 * <p>측정한 바로 댓글의 100% 가 글이 올라온 뒤 이틀 안에 달리므로 {@link #TTL_MS} 를 3일로 둔다.
 * 상한 {@link #MAX} 는 그 안에서도 목록이 무한정 길어지지 않게 막는 두 번째 울타리다.
 */
final class TrackedPosts {
    static final int MY_POST = 1;
    static final int MY_COMMENT = 2;
    static final int MAX = 20;
    static final long TTL_MS = 3L * 24 * 60 * 60 * 1000;

    private static final char UNIT = 0x1f;
    private static final char RECORD = 0x1e;
    private static final Pattern CODE = Pattern.compile("^[cpbm][0-9a-f]{1,40}$");
    /** 등록이 최근인 것부터. 상한을 넘기면 뒤쪽이 잘린다. */
    private static final Comparator<Tracked> NEWEST_FIRST = new Comparator<Tracked>() {
        @Override public int compare(Tracked left, Tracked right) {
            return Long.compare(right.added, left.added);
        }
    };
    /** 확인한 지 오래된 것부터. 라운드로빈 순서다. */
    private static final Comparator<Tracked> STALEST_FIRST = new Comparator<Tracked>() {
        @Override public int compare(Tracked left, Tracked right) {
            return Long.compare(left.checked, right.checked);
        }
    };

    private TrackedPosts() {}

    static final class Tracked {
        final String url;
        final String postCode;
        final String boardCode;
        final long added;
        final long checked;
        final int reason;

        Tracked(String url, String postCode, String boardCode, long added, long checked, int reason) {
            this.url = url;
            this.postCode = postCode;
            this.boardCode = boardCode;
            this.added = added;
            this.checked = checked;
            this.reason = reason;
        }
    }

    /**
     * 이미 있는 글이면 사유만 합치고, 없으면 넣는다. 어느 쪽이든 만료와 상한을 다시 적용한다.
     * 값이 형식에 맞지 않으면 목록을 그대로 돌려준다.
     */
    static List<Tracked> add(List<Tracked> list, String url, String postCode, String boardCode,
                             int reason, long now) {
        if (url == null || !url.startsWith("https://") || url.indexOf(UNIT) >= 0
                || url.indexOf(RECORD) >= 0) return list;
        if (!CODE.matcher(postCode).matches() || postCode.charAt(0) != 'p') return list;
        if (!CODE.matcher(boardCode).matches() || boardCode.charAt(0) != 'b') return list;

        List<Tracked> next = new ArrayList<>();
        boolean merged = false;
        for (Tracked item : list) {
            if (item.url.equals(url)) {
                next.add(new Tracked(item.url, item.postCode, item.boardCode,
                        item.added, item.checked, item.reason | reason));
                merged = true;
            } else {
                next.add(item);
            }
        }
        // checked 를 0 으로 두면 새 글이 다음 사이클의 확인 대기열 맨 앞에 선다.
        if (!merged) next.add(new Tracked(url, postCode, boardCode, now, 0L, reason));
        return prune(next, now);
    }

    /** 만료된 항목을 버리고 등록이 최근인 것부터 {@link #MAX} 개만 남긴다. */
    static List<Tracked> prune(List<Tracked> list, long now) {
        List<Tracked> next = new ArrayList<>();
        for (Tracked item : list) {
            if (now - item.added < TTL_MS) next.add(item);
        }
        Collections.sort(next, NEWEST_FIRST);
        return next.size() <= MAX ? next : new ArrayList<>(next.subList(0, MAX));
    }

    /** 확인한 지 오래된 것부터 최대 {@code limit} 개. */
    static List<Tracked> due(List<Tracked> list, int limit) {
        List<Tracked> queue = new ArrayList<>(list);
        Collections.sort(queue, STALEST_FIRST);
        return queue.size() <= limit ? queue : new ArrayList<>(queue.subList(0, limit));
    }

    static List<Tracked> markChecked(List<Tracked> list, String url, long now) {
        List<Tracked> next = new ArrayList<>();
        for (Tracked item : list) {
            next.add(item.url.equals(url)
                    ? new Tracked(item.url, item.postCode, item.boardCode, item.added, now, item.reason)
                    : item);
        }
        return next;
    }

    static String encode(List<Tracked> list) {
        StringBuilder text = new StringBuilder();
        for (Tracked item : list) {
            if (text.length() > 0) text.append(RECORD);
            text.append(item.url).append(UNIT).append(item.postCode).append(UNIT)
                    .append(item.boardCode).append(UNIT).append(item.added).append(UNIT)
                    .append(item.checked).append(UNIT).append(item.reason);
        }
        return text.toString();
    }

    static List<Tracked> decode(String text) {
        List<Tracked> list = new ArrayList<>();
        if (text == null || text.isEmpty()) return list;
        for (String row : text.split(String.valueOf(RECORD), -1)) {
            String[] parts = row.split(String.valueOf(UNIT), -1);
            if (parts.length != 6) continue;
            // 저장을 거친 값은 다시 확인한다. 캐시가 검사 범위를 넓힐 수 없게.
            if (!parts[0].startsWith("https://")) continue;
            if (!CODE.matcher(parts[1]).matches() || !CODE.matcher(parts[2]).matches()) continue;
            try {
                list.add(new Tracked(parts[0], parts[1], parts[2],
                        Long.parseLong(parts[3]), Long.parseLong(parts[4]),
                        Integer.parseInt(parts[5])));
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./test-android.ps1`
Expected: `SELF-TEST PASS`

- [ ] **Step 5: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/TrackedPosts.java \
        IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java \
        test-android.ps1 build-android.ps1
git commit -m "feat: keep a capped, expiring list of posts to watch"
```

---

### Task 4: CommentRules — 무엇을 알릴지 판단

**Files:**
- Create: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentRules.java`
- Modify: `test-android.ps1`, `build-android.ps1`
- Test: `IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java`

**Interfaces:**
- Consumes: `CommentParser.Comment` (Task 1), `TrackedPosts.MY_POST` / `TrackedPosts.MY_COMMENT` (Task 3)
- Produces:
  - `static List<CommentParser.Comment> CommentRules.pick(List<CommentParser.Comment> comments, Set<String> seen, String nickname, int reason, boolean myPosts, boolean myReplies)`

- [ ] **Step 1: 실패하는 테스트 작성**

`SelfTest.java`에 이어 쓴다. 설계 문서가 쓴 실제 예시를 그대로 검증한다.

```java
        // ------------------------------------------------------------ 알림 규칙
        List<CommentParser.Comment> thread = new ArrayList<>();
        thread.add(new CommentParser.Comment("c1", "위즐리어카", "m1", "밑에 영상 링크가 잘못된 것 같아염"));
        thread.add(new CommentParser.Comment("c2", "유메루", "m2", "이거 맞아염"));
        thread.add(new CommentParser.Comment("c3", "현랑화", "m3", "앞으로도 즐겁고 행복한 활동 하길!"));
        thread.add(new CommentParser.Comment("c4", "유메루", "m2", "앞으로도 잘 부탁해!!"));
        Set<String> none = new HashSet<>();

        // 내 댓글 바로 다음 한 건만.
        List<CommentParser.Comment> mine = CommentRules.pick(
                thread, none, "위즐리어카", TrackedPosts.MY_COMMENT, true, true);
        assert mine.size() == 1 : "바로 다음 한 건, 실제 " + mine.size();
        assert "c2".equals(mine.get(0).code);

        List<CommentParser.Comment> hers = CommentRules.pick(
                thread, none, "현랑화", TrackedPosts.MY_COMMENT, true, true);
        assert hers.size() == 1 && "c4".equals(hers.get(0).code) : "c3 다음은 c4";

        // 내 글이면 그 글의 새 댓글 전부. 단 내가 쓴 댓글은 빼고.
        List<CommentParser.Comment> onMyPost = CommentRules.pick(
                thread, none, "유메루", TrackedPosts.MY_POST, true, true);
        assert onMyPost.size() == 2 : "내 댓글 두 개를 뺀 나머지, 실제 " + onMyPost.size();
        assert "c1".equals(onMyPost.get(0).code) && "c3".equals(onMyPost.get(1).code);

        // 이미 본 댓글은 다시 알리지 않는다.
        Set<String> seenC2 = new HashSet<>(Arrays.asList("c2"));
        assert CommentRules.pick(thread, seenC2, "위즐리어카",
                TrackedPosts.MY_COMMENT, true, true).isEmpty() : "본 댓글은 제외";

        // 스위치가 꺼져 있으면 그 사유는 아무것도 고르지 않는다.
        assert CommentRules.pick(thread, none, "위즐리어카",
                TrackedPosts.MY_COMMENT, true, false).isEmpty() : "스위치2 off";
        assert CommentRules.pick(thread, none, "유메루",
                TrackedPosts.MY_POST, false, true).isEmpty() : "스위치1 off";

        // 두 사유가 겹쳐도 같은 댓글이 두 번 나오지 않는다.
        List<CommentParser.Comment> both = CommentRules.pick(thread, none, "위즐리어카",
                TrackedPosts.MY_POST | TrackedPosts.MY_COMMENT, true, true);
        assert both.size() == 3 : "c2·c3·c4 세 건, 실제 " + both.size();
        assert "c2".equals(both.get(0).code) && "c4".equals(both.get(2).code) : "순서 유지";

        // 닉네임이 비어 있으면 아무것도 알리지 않는다.
        assert CommentRules.pick(thread, none, "", TrackedPosts.MY_POST, true, true).isEmpty()
                : "닉네임 없으면 기능 자체가 꺼진 상태";
```

`SelfTest.java` import에 `java.util.HashSet`과 `java.util.Set`을 추가한다.

- [ ] **Step 2: 테스트가 실패하는지 확인**

`test-android.ps1`의 `$sources`와 `build-android.ps1`의 컴파일 줄에 `CommentRules.java`를 추가한 뒤:

```powershell
$commentRules = Join-Path $input.FullName 'src\com\illusionlive\notifier\CommentRules.java'
& $javac --release 8 -encoding UTF-8 -Xlint:all -Werror -d $testClasses.FullName $parser $colors $commentParser $trackedPosts $commentRules $selfTest
```

Run: `./test-android.ps1`
Expected: 컴파일 실패. `cannot find symbol ... class CommentRules`

- [ ] **Step 3: 구현**

```java
package com.illusionlive.notifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 한 글의 댓글 목록에서 알릴 것을 고른다.
 *
 * <p>Android API 를 쓰지 않는다. {@code SelfTest} 가 android.jar 없이 컴파일한다.
 *
 * <p>사이트가 댓글을 평면으로 저장해 답변 대상을 기록하지 않는다. 그래서 "내 댓글에 달린 답"은
 * 순서로 추정한다 — 내 댓글 바로 다음 한 건. 글당 댓글이 평균 1.8 개라 사이에 제삼자가 끼어들
 * 여지가 작다. 끼어들면 놓치고, 내 댓글이 마지막일 때 무관한 댓글이 달리면 헛알림이 한 건 난다.
 * ponytail: 사이트가 부모 댓글을 기록하기 시작하면 그 값으로 바꾼다.
 */
final class CommentRules {
    private CommentRules() {}

    /**
     * @param comments  화면에 보이는 순서 그대로의 댓글
     * @param seen      이미 알린 댓글 코드
     * @param nickname  내 닉네임. 비어 있으면 아무것도 고르지 않는다
     * @param reason    이 글을 추적하는 사유. {@link TrackedPosts#MY_POST} 와
     *                  {@link TrackedPosts#MY_COMMENT} 의 비트합
     * @param myPosts   "내 글에 달린 댓글" 스위치
     * @param myReplies "내 댓글에 달린 답" 스위치
     * @return 알릴 댓글. 입력 순서를 지키고 같은 댓글이 두 번 들어가지 않는다
     */
    static List<CommentParser.Comment> pick(List<CommentParser.Comment> comments, Set<String> seen,
                                            String nickname, int reason,
                                            boolean myPosts, boolean myReplies) {
        Map<String, CommentParser.Comment> picked = new LinkedHashMap<>();
        if (nickname == null || nickname.isEmpty()) return new ArrayList<>(picked.values());

        boolean onMyPost = myPosts && (reason & TrackedPosts.MY_POST) != 0;
        boolean afterMyComment = myReplies && (reason & TrackedPosts.MY_COMMENT) != 0;

        for (int i = 0; i < comments.size(); i++) {
            CommentParser.Comment comment = comments.get(i);
            if (seen.contains(comment.code)) continue;
            if (nickname.equals(comment.author)) continue; // 내가 쓴 것

            boolean follows = i > 0 && nickname.equals(comments.get(i - 1).author);
            if (onMyPost || (afterMyComment && follows)) picked.put(comment.code, comment);
        }
        return new ArrayList<>(picked.values());
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./test-android.ps1`
Expected: `SELF-TEST PASS`

- [ ] **Step 5: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentRules.java \
        IllusionLiveNotifier.Android/tests/com/illusionlive/notifier/SelfTest.java \
        test-android.ps1 build-android.ps1
git commit -m "feat: choose which new comments to notify about"
```

---

### Task 5: CommentChecker — 설정 키와 상태

**Files:**
- Create: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentChecker.java`

**Interfaces:**
- Consumes: `FeedChecker.prefs(Context)`, `TrackedPosts`, `CommentRules`
- Produces:
  - `static String CommentChecker.nickname(Context)` — 없으면 `""`
  - `static void CommentChecker.setNickname(Context, String)` — 처음 저장하면 두 스위치를 켜고, 값이 바뀌면 수집 상태를 모두 지운다
  - `static boolean CommentChecker.enabled(Context)` — 닉네임이 있고 스위치가 하나라도 켜져 있으면 true
  - 상수 `KEY_NICKNAME`, `KEY_MY_POSTS`, `KEY_MY_REPLIES`, `KEY_TRACKED`, `KEY_SEEN`, `KEY_SCANNED`, `KEY_INITIALIZED`

이 클래스는 Android API를 쓰므로 `SelfTest`가 검증하지 못한다. 판단 로직은 전부 Task 3·4의 순수 클래스에 있고, 여기서는 그것을 호출만 한다. 검증은 `./build-android.ps1` 컴파일 통과와 Task 10의 수동 확인이다.

- [ ] **Step 1: 클래스 만들기**

```java
package com.illusionlive.notifier;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 댓글 알림. 기존 RSS 사이클 뒤에 붙어 돈다.
 *
 * <p>사이트에 로그인하지 않는다. 댓글은 비로그인으로 읽히고, 신원은 사용자가 설정에 적은
 * 닉네임 문자열 하나로만 판단한다. 그래서 동명이인은 구분하지 못한다.
 */
final class CommentChecker {
    static final String KEY_NICKNAME = "nickname";
    static final String KEY_MY_POSTS = "comment_my_posts";
    static final String KEY_MY_REPLIES = "comment_my_replies";
    static final String KEY_TRACKED = "tracked_posts";
    static final String KEY_SEEN = "seen_comment_ids";
    static final String KEY_SCANNED = "scanned_posts";
    static final String KEY_INITIALIZED = "comments_initialized";

    private static final int MAX_SEEN = 500;
    private static final int MAX_SCANNED = 200;

    private CommentChecker() {}

    static String nickname(Context context) {
        return FeedChecker.prefs(context).getString(KEY_NICKNAME, "").trim();
    }

    static boolean myPostsEnabled(Context context) {
        return FeedChecker.prefs(context).getBoolean(KEY_MY_POSTS, false);
    }

    static boolean myRepliesEnabled(Context context) {
        return FeedChecker.prefs(context).getBoolean(KEY_MY_REPLIES, false);
    }

    static boolean enabled(Context context) {
        return !nickname(context).isEmpty()
                && (myPostsEnabled(context) || myRepliesEnabled(context));
    }

    /**
     * 닉네임을 저장한다. 값이 바뀌면 모아 둔 추적 목록과 본 댓글 기록을 전부 버린다. 남겨 두면
     * 이전 닉네임 기준으로 고른 글에서 엉뚱한 알림이 나간다. 처음 저장하는 경우에는 두 스위치를
     * 켜 준다.
     */
    static void setNickname(Context context, String value) {
        SharedPreferences preferences = FeedChecker.prefs(context);
        String next = value == null ? "" : value.trim();
        String previous = nickname(context);
        if (next.equals(previous)) return;

        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_NICKNAME, next)
                .remove(KEY_TRACKED)
                .remove(KEY_SEEN)
                .remove(KEY_SCANNED)
                .putBoolean(KEY_INITIALIZED, false);
        if (previous.isEmpty() && !next.isEmpty()) {
            editor.putBoolean(KEY_MY_POSTS, true).putBoolean(KEY_MY_REPLIES, true);
        }
        editor.commit();
    }

    private static Set<String> stringSet(SharedPreferences preferences, String key) {
        return new HashSet<>(preferences.getStringSet(key, Collections.<String>emptySet()));
    }

    /**
     * 이번 사이클에 본 값을 먼저 채우고 남는 자리만 옛 값으로 메운다. {@link FeedChecker} 의
     * seen_ids 와 같은 방식이다. 순서를 뒤집으면 방금 본 댓글이 상한에 밀려 빠지고, 다음
     * 사이클에 같은 댓글을 새 것으로 보아 다시 알린다.
     */
    private static Set<String> capped(Set<String> fresh, Set<String> old, int max) {
        Set<String> next = new HashSet<>(fresh);
        for (String value : old) {
            if (next.size() >= max) break;
            next.add(value);
        }
        return next;
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./test-android.ps1`
Expected: `SELF-TEST PASS` — 이 파일은 테스트 컴파일 목록에 넣지 않는다. Android API를 쓰므로 넣으면 깨진다.

`stringSet`과 `capped`는 Task 6이 쓴다. 지금은 미사용이지만 `private` 메서드 미사용은 `-Xlint:all`이 경고하지 않으므로 빌드는 통과한다. 확인이 필요하면 Task 6까지 진행한 뒤 `./build-android.ps1`을 돌린다.

- [ ] **Step 3: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentChecker.java
git commit -m "feat: add comment-notification settings state"
```

---

### Task 6: CommentChecker — 네트워크와 사이클

**Files:**
- Modify: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentChecker.java`

**Interfaces:**
- Consumes: `FeedParser.Post` (필드 `id`, `boardSlug`, `title`, `author`, `published`, `url`), `CommentParser`, `TrackedPosts`, `CommentRules`
- Produces:
  - `static void CommentChecker.run(Context context, List<FeedParser.Post> posts, boolean sendNotifications)` — 호출자의 백그라운드 스레드에서 그대로 돈다. 예외를 밖으로 던지지 않는다

- [ ] **Step 1: 상수와 HTTP 헬퍼 추가**

`CommentChecker`에 넣는다. import에 `java.io.ByteArrayOutputStream`, `java.io.IOException`, `java.io.InputStream`, `java.io.OutputStream`, `java.net.HttpURLConnection`, `java.net.URL`, `java.net.URLEncoder`, `java.util.ArrayList`, `java.util.List`를 추가한다.

```java
    private static final String COMMENT_URL = "https://www.illusionlive.com/ajax/post_comment_paging.cm";
    /** 한 사이클에 새로 들여다볼 글 수. 글 페이지가 61 KB 라 발견 비용의 대부분이 여기서 난다. */
    private static final int SCAN_PER_CYCLE = 3;
    /** 한 사이클에 댓글을 다시 확인할 글 수. 추적 수와 무관하게 데이터 사용량을 묶는 상한이다. */
    private static final int CHECK_PER_CYCLE = 8;
    private static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final int MAX_COMMENT_BYTES = 256 * 1024;

    private static String get(String url, int limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent",
                "IllusionLiveNotifier-Android/1.0 (+https://www.illusionlive.com)");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** 댓글 목록. 세 값이 모두 있어야 사이트가 응답한다. */
    private static String fetchComments(TrackedPosts.Tracked post) throws IOException {
        String form = "board_code=" + URLEncoder.encode(post.boardCode, "UTF-8")
                + "&post_code=" + URLEncoder.encode(post.postCode, "UTF-8")
                + "&current_page=1";
        HttpURLConnection connection = (HttpURLConnection) new URL(COMMENT_URL).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("User-Agent",
                "IllusionLiveNotifier-Android/1.0 (+https://www.illusionlive.com)");
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(form.getBytes("UTF-8"));
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return unwrapHtml(readLimited(input, MAX_COMMENT_BYTES));
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 응답은 {@code {"msg":"SUCCESS","html":"…"}} 형태다. html 값만 꺼내 역슬래시 이스케이프를
     * 되돌린다. JSON 파서를 들이지 않는 이유는 필요한 필드가 이 하나뿐이기 때문이다.
     * ponytail: 다른 필드가 필요해지면 org.json 으로 바꾼다.
     */
    private static String unwrapHtml(String json) {
        int at = json.indexOf("\"html\":\"");
        if (at < 0) return "";
        int from = at + 8;
        StringBuilder html = new StringBuilder();
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n': html.append('\n'); break;
                    case 'r': html.append('\r'); break;
                    case 't': html.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            html.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: html.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                html.append(c);
            }
        }
        return html.toString();
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("응답이 " + limit + " 바이트를 넘었습니다");
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), "UTF-8");
    }
```

- [ ] **Step 2: 사이클 구현**

알림은 댓글 한 건마다 "어느 글에 달렸는지"를 알아야 문구를 만들고 주소를 열 수 있다. `CommentParser.Comment`에는 글 정보가 없으므로 둘을 묶는 작은 클래스를 먼저 만든다.

```java
    /** 알릴 댓글 한 건과 그 댓글이 달린 글. */
    private static final class Hit {
        final CommentParser.Comment comment;
        final String postUrl;
        final String postTitle;

        Hit(CommentParser.Comment comment, String postUrl, String postTitle) {
            this.comment = comment;
            this.postUrl = postUrl;
            this.postTitle = postTitle;
        }
    }

    /** 캐시에 있으면 글 제목, 없으면 빈 문자열. 알림 문구에만 쓴다. */
    private static String title(Context context, String url) {
        for (FeedParser.Post post : FeedChecker.cachedPosts(context)) {
            if (post.url.equals(url)) return post.title;
        }
        return "";
    }

    /**
     * RSS 처리가 끝난 뒤 같은 스레드에서 이어 돈다. 세 단계다 — 내 글 등록, 발견 스캔,
     * 추적 재확인. 어느 단계가 네트워크 오류로 실패해도 나머지는 계속한다.
     */
    static void run(Context context, List<FeedParser.Post> posts, boolean sendNotifications) {
        if (!enabled(context)) return;

        SharedPreferences preferences = FeedChecker.prefs(context);
        String nickname = nickname(context);
        boolean myPosts = myPostsEnabled(context);
        boolean myReplies = myRepliesEnabled(context);
        boolean initialized = preferences.getBoolean(KEY_INITIALIZED, false);
        long now = System.currentTimeMillis();

        List<TrackedPosts.Tracked> tracked =
                TrackedPosts.decode(preferences.getString(KEY_TRACKED, ""));
        Set<String> scanned = stringSet(preferences, KEY_SCANNED);
        Set<String> seen = stringSet(preferences, KEY_SEEN);
        // 상한을 적용할 때 이번 사이클 것을 먼저 채우려면 무엇이 새로 들어왔는지 알아야 한다.
        Set<String> scannedBefore = new HashSet<>(scanned);
        Set<String> seenNow = new HashSet<>();

        if (myPosts) tracked = registerMyPosts(posts, tracked, scanned, nickname, now);
        if (myReplies) tracked = discover(posts, tracked, scanned, nickname, now);

        List<Hit> fresh = new ArrayList<>();
        for (TrackedPosts.Tracked post : TrackedPosts.due(tracked, CHECK_PER_CYCLE)) {
            List<CommentParser.Comment> comments;
            try {
                comments = CommentParser.parseComments(fetchComments(post));
            } catch (Exception ignored) {
                // checked 를 갱신하지 않으므로 이 글이 다음 사이클 대기열 앞에 그대로 남는다.
                continue;
            }
            tracked = TrackedPosts.markChecked(tracked, post.url, now);

            List<CommentParser.Comment> picked = CommentRules.pick(
                    comments, seen, nickname, post.reason, myPosts, myReplies);
            // 고른 뒤에 기록한다. 순서가 바뀌면 이번에 알릴 것까지 본 것으로 표시된다.
            for (CommentParser.Comment comment : comments) seenNow.add(comment.code);

            // 첫 시딩에서는 기준점만 잡고 알리지 않는다.
            if (!initialized) continue;
            String postTitle = title(context, post.url);
            for (CommentParser.Comment comment : picked) {
                fresh.add(new Hit(comment, post.url, postTitle));
            }
        }

        Set<String> scannedNow = new HashSet<>(scanned);
        scannedNow.removeAll(scannedBefore);

        preferences.edit()
                .putString(KEY_TRACKED, TrackedPosts.encode(tracked))
                .putStringSet(KEY_SCANNED, capped(scannedNow, scannedBefore, MAX_SCANNED))
                .putStringSet(KEY_SEEN, capped(seenNow, seen, MAX_SEEN))
                .putBoolean(KEY_INITIALIZED, true)
                .commit();

        if (sendNotifications && !fresh.isEmpty()) notifyComments(context, fresh);
    }
```

- [ ] **Step 3: 등록과 발견 스캔 구현**

```java
    /** RSS 에서 내가 쓴 글을 골라 추적에 넣는다. 두 코드를 얻으려 글 페이지를 한 번 받는다. */
    private static List<TrackedPosts.Tracked> registerMyPosts(
            List<FeedParser.Post> posts, List<TrackedPosts.Tracked> tracked,
            Set<String> scanned, String nickname, long now) {
        for (FeedParser.Post post : posts) {
            if (!nickname.equals(post.author)) continue;
            if (contains(tracked, post.url)) continue;
            tracked = withCodes(tracked, post.url, TrackedPosts.MY_POST, now);
            scanned.add(post.id); // 내 글은 발견 스캔이 다시 열어 볼 필요가 없다
        }
        return tracked;
    }

    /**
     * 아직 안 본 글을 사이클당 {@link #SCAN_PER_CYCLE} 개까지 열어, 내 댓글이 있으면 추적에
     * 넣는다. 있든 없든 {@link #KEY_SCANNED} 에 적어 두 번 열지 않는다.
     */
    private static List<TrackedPosts.Tracked> discover(
            List<FeedParser.Post> posts, List<TrackedPosts.Tracked> tracked,
            Set<String> scanned, String nickname, long now) {
        int budget = SCAN_PER_CYCLE;
        for (FeedParser.Post post : posts) {
            if (budget <= 0) break;
            if (scanned.contains(post.id) || contains(tracked, post.url)) continue;
            budget--;
            scanned.add(post.id);

            String page;
            try {
                page = get(post.url, MAX_PAGE_BYTES);
            } catch (Exception ignored) {
                scanned.remove(post.id); // 실패한 글은 다음 사이클에 다시 시도한다
                continue;
            }
            String postCode = CommentParser.postCode(page);
            String boardCode = CommentParser.boardCode(page);
            if (postCode.isEmpty() || boardCode.isEmpty()) continue;

            TrackedPosts.Tracked probe =
                    new TrackedPosts.Tracked(post.url, postCode, boardCode, now, 0L, 0);
            try {
                for (CommentParser.Comment comment : CommentParser.parseComments(fetchComments(probe))) {
                    if (nickname.equals(comment.author)) {
                        tracked = TrackedPosts.add(tracked, post.url, postCode, boardCode,
                                TrackedPosts.MY_COMMENT, now);
                        break;
                    }
                }
            } catch (Exception ignored) {
                scanned.remove(post.id);
            }
        }
        return tracked;
    }

    /** 글 페이지에서 두 코드를 받아 추적에 넣는다. 실패하면 목록을 그대로 돌려준다. */
    private static List<TrackedPosts.Tracked> withCodes(
            List<TrackedPosts.Tracked> tracked, String url, int reason, long now) {
        try {
            String page = get(url, MAX_PAGE_BYTES);
            return TrackedPosts.add(tracked, url,
                    CommentParser.postCode(page), CommentParser.boardCode(page), reason, now);
        } catch (Exception ignored) {
            return tracked;
        }
    }

    private static boolean contains(List<TrackedPosts.Tracked> tracked, String url) {
        for (TrackedPosts.Tracked item : tracked) {
            if (item.url.equals(url)) return true;
        }
        return false;
    }
```

`TrackedPosts.Tracked` 생성자는 package-private이므로 같은 패키지인 여기서 바로 쓸 수 있다.

- [ ] **Step 4: 컴파일 확인**

Run: `./build-android.ps1`
Expected: 빌드 성공. `ILLUSIONLIVE_KEYSTORE_PASSWORD`가 없으면 서명 단계에서 멈추는데, 그 전에 `Parser self-test`와 앱 컴파일이 지나갔으면 이 단계 목적은 달성이다.

- [ ] **Step 5: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentChecker.java
git commit -m "feat: fetch and diff comments for tracked posts"
```

---

### Task 7: CommentChecker — 알림

**Files:**
- Modify: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentChecker.java`

**Interfaces:**
- Consumes: Task 6의 `Hit`
- Produces:
  - `static void CommentChecker.ensureNotificationChannel(Context)` — `MainActivity`가 설정에서 채널을 만들 때 쓴다

- [ ] **Step 1: 채널과 알림 구현**

import에 `android.Manifest`, `android.app.Notification`, `android.app.NotificationChannel`, `android.app.NotificationManager`, `android.app.PendingIntent`, `android.content.Intent`, `android.content.pm.PackageManager`, `android.net.Uri`, `android.os.Build`를 추가한다.

```java
    private static final String CHANNEL_ID = "new_comments";
    /** 여러 건을 묶을 때 쓰는 고정 id. 글 알림의 8702 와 겹치지 않게 둔다. */
    private static final int SUMMARY_ID = 8703;

    static void ensureNotificationChannel(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "댓글 알림", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("내 글에 달린 댓글과 내 댓글에 달린 답");
        manager.createNotificationChannel(channel);
    }

    private static void notifyComments(Context context, List<Hit> hits) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        ensureNotificationChannel(context);
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent intent;
        String title;
        String text;
        int notificationId;
        if (hits.size() == 1) {
            Hit hit = hits.get(0);
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(hit.postUrl));
            title = hit.comment.author + " 님의 댓글";
            text = hit.postTitle.isEmpty()
                    ? hit.comment.body
                    : hit.comment.body + " · " + hit.postTitle;
            notificationId = hit.comment.code.hashCode();
        } else {
            intent = new Intent(context, MainActivity.class);
            title = "새 댓글 " + hits.size() + "개";
            text = hits.get(0).comment.author + " 외 " + (hits.size() - 1) + "명";
            notificationId = SUMMARY_ID;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL);
        if (hits.size() > 1) {
            Notification.InboxStyle style = new Notification.InboxStyle();
            for (int i = 0; i < Math.min(5, hits.size()); i++) {
                Hit hit = hits.get(i);
                style.addLine(hit.comment.author + ": " + hit.comment.body);
            }
            if (hits.size() > 5) style.setSummaryText("외 " + (hits.size() - 5) + "개");
            builder.setStyle(style).setNumber(hits.size());
        } else {
            builder.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        manager.notify(notificationId, builder.build());
    }
```

알림을 눌러 여는 주소는 `TrackedPosts`가 저장할 때 `https://` 로 시작하는지 확인한 값이고 RSS 파서가 호스트까지 검사한 링크다. 댓글 HTML에서 꺼낸 주소는 쓰지 않는다.

- [ ] **Step 2: 컴파일 확인**

Run: `./build-android.ps1`
Expected: 앱 컴파일 통과

- [ ] **Step 3: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/CommentChecker.java
git commit -m "feat: send comment notifications on their own channel"
```

---

### Task 8: FeedChecker 연결

**Files:**
- Modify: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/FeedChecker.java:155-180`

**Interfaces:**
- Consumes: `CommentChecker.run(Context, List<FeedParser.Post>, boolean)`
- Produces: 없음

- [ ] **Step 1: process 끝에 호출 넣기**

`process` 메서드의 마지막 두 줄을 바꾼다. 기존:

```java
        if (initialized && sendNotifications && !matched.isEmpty()) notifyPosts(context, matched);
        return new Result(merged, fresh.size(), matched.size(), !initialized, false, null);
```

바꾼 뒤:

```java
        if (initialized && sendNotifications && !matched.isEmpty()) notifyPosts(context, matched);

        // 댓글 확인은 같은 사이클, 같은 스레드에서 이어 돈다. 닉네임이 없으면 즉시 돌아오므로
        // 기능을 쓰지 않는 설치에는 요청이 한 건도 늘지 않는다. 여기서 던지는 예외는 없다.
        CommentChecker.run(context, merged, sendNotifications);

        return new Result(merged, fresh.size(), matched.size(), !initialized, false, null);
```

`posts`가 아니라 `merged`를 넘긴다. `merged`는 이번 RSS와 캐시를 합친 목록이라 이번 응답에 없는 최근 글도 들어 있고, 그래야 발견 스캔이 창 전체를 훑는다.

- [ ] **Step 2: 컴파일 확인**

Run: `./build-android.ps1`
Expected: 앱 컴파일 통과

- [ ] **Step 3: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/FeedChecker.java
git commit -m "feat: run the comment check after each feed poll"
```

---

### Task 9: 첫 실행 안내를 Tutorial 로 옮기고 닉네임 페이지 추가

**Files:**
- Create: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/Tutorial.java`
- Modify: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/MainActivity.java:581-694`, `:856-960`
- Modify: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/TutorialArt.java`

**Interfaces:**
- Consumes: `MainActivity`의 UI 헬퍼(아래에서 package-private으로 바꾼다), `CommentChecker.setNickname`
- Produces: `static void Tutorial.maybeShow(MainActivity activity)`

- [ ] **Step 1: MainActivity 헬퍼 10개를 package-private 으로**

`private` 한 단어씩만 지운다. 시그니처와 본문은 건드리지 않는다.

```
MainActivity.java:856  rounded
MainActivity.java:864  ripple
MainActivity.java:879  card
MainActivity.java:888  bandCard
MainActivity.java:895  sectionTitle
MainActivity.java:910  groupLabel
MainActivity.java:927  switchRow
MainActivity.java:943  text
MainActivity.java:952  matchWrap
MainActivity.java:958  dp
```

색상 상수 `BRAND`, `ON_BRAND`, `MUTED`, `LINE`, `SURFACE`도 `Tutorial`이 읽으므로 `private`이 붙어 있으면 뗀다.

- [ ] **Step 2: Tutorial.java 만들기**

`MainActivity`의 `TUTORIAL` 배열과 `maybeShowTutorial()` 본문(581~694줄)을 그대로 옮기고, `this`를 `activity`로 바꾼다. 안내 페이지 하나를 뒤에 더한다.

```java
package com.illusionlive.notifier;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 첫 실행에 한 번 뜨는 안내. {@link MainActivity} 에서 떼어 냈다 — 닉네임 입력 페이지가 붙으면서
 * 한 파일에 두기에는 커졌다.
 */
final class Tutorial {
    /** 각 쪽의 제목과 본문. 그림은 {@link TutorialArt} 가 그린다. */
    private static final String[][] PAGES = {
            {"알림 받을 게시판 고르기",
                    "오른쪽 위 톱니바퀴를 누르면 게시판 목록이 열립니다.\n체크한 게시판의 새 글만 알려 드립니다."},
            {"당겨서 새로고침",
                    "목록 맨 위에서 아래로 당기면\n새 글을 바로 확인합니다."},
            {"글 열어보기",
                    "글을 누르면 브라우저에서\n원문이 열립니다."},
            {"앱을 닫아도 알림",
                    "백그라운드에서 새 글을 확인해 알림을 보냅니다.\nAndroid 절전 상태에서는 조금 늦어질 수 있습니다."},
            {"지금 있는 글은 알리지 않아요",
                    "첫 실행 시점의 글은 기준으로만 저장하고,\n이후 올라오는 새 글부터 알려 드립니다."},
            {"댓글 알림 받기",
                    "사이트에서 쓰는 닉네임을 정확히 적어 주세요.\n내 글에 달린 댓글과 내 댓글에 달린 답을 알려 드립니다.\n비워 두면 댓글 알림을 끕니다."}
    };

    /** 닉네임 입력칸이 있는 쪽. 마지막 쪽이다. */
    private static final int NICKNAME_PAGE = PAGES.length - 1;

    private Tutorial() {}

    static void maybeShow(final MainActivity activity) {
        final SharedPreferences preferences = FeedChecker.prefs(activity);
        if (preferences.getBoolean(FeedChecker.KEY_TUTORIAL_SEEN, false)) return;

        final FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setClickable(true); // 밑의 목록으로 탭이 새지 않게

        LinearLayout card = activity.card();

        final TutorialArt art = new TutorialArt(activity);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, activity.dp(178));
        artParams.setMargins(0, activity.dp(4), 0, activity.dp(16));
        card.addView(art, artParams);

        final TextView title = activity.sectionTitle("");
        card.addView(title, activity.matchWrap(activity.dp(8)));

        final TextView body = activity.text("", 14.5f);
        body.setTextColor(MainActivity.MUTED);
        body.setLineSpacing(activity.dp(4), 1f);
        body.setMinLines(2); // 쪽을 넘겨도 카드 높이가 흔들리지 않게
        card.addView(body, activity.matchWrap(activity.dp(16)));

        final EditText nickname = new EditText(activity);
        nickname.setHint("닉네임");
        nickname.setSingleLine(true);
        nickname.setInputType(InputType.TYPE_CLASS_TEXT);
        nickname.setTextSize(15f);
        nickname.setPadding(activity.dp(12), activity.dp(10), activity.dp(12), activity.dp(10));
        nickname.setBackground(activity.rounded(MainActivity.SURFACE, 8, MainActivity.LINE));
        nickname.setVisibility(View.GONE);
        card.addView(nickname, activity.matchWrap(activity.dp(16)));

        final LinearLayout dots = new LinearLayout(activity);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < PAGES.length; i++) {
            LinearLayout.LayoutParams dotParams =
                    new LinearLayout.LayoutParams(activity.dp(7), activity.dp(7));
            dotParams.setMargins(activity.dp(4), 0, activity.dp(4), 0);
            dots.addView(new View(activity), dotParams);
        }
        card.addView(dots, activity.matchWrap(activity.dp(16)));

        final TextView skip = activity.text("건너뛰기", 15f);
        skip.setTextColor(MainActivity.MUTED);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(activity.dp(16), activity.dp(13), activity.dp(16), activity.dp(13));
        skip.setBackground(activity.ripple(MainActivity.SURFACE, 0, MainActivity.MUTED));

        final TextView next = activity.text("", 15.5f);
        next.setTypeface(Typeface.DEFAULT_BOLD);
        next.setTextColor(MainActivity.ON_BRAND);
        next.setGravity(Gravity.CENTER);
        next.setPadding(0, activity.dp(13), 0, activity.dp(13));
        next.setBackground(activity.ripple(MainActivity.BRAND, 0, MainActivity.ON_BRAND));

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(skip, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1);
        nextParams.setMargins(activity.dp(10), 0, 0, 0);
        buttons.addView(next, nextParams);
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        final int[] step = {0};
        final Runnable render = new Runnable() {
            @Override public void run() {
                art.setStep(step[0]);
                title.setText(PAGES[step[0]][0]);
                body.setText(PAGES[step[0]][1]);
                nickname.setVisibility(step[0] == NICKNAME_PAGE ? View.VISIBLE : View.GONE);
                for (int i = 0; i < dots.getChildCount(); i++) {
                    dots.getChildAt(i).setBackground(activity.rounded(
                            i == step[0] ? MainActivity.BRAND : MainActivity.LINE, 4, 0));
                }
                boolean last = step[0] == PAGES.length - 1;
                next.setText(last ? "시작하기" : "다음");
                skip.setVisibility(last ? View.GONE : View.VISIBLE);
            }
        };

        final Runnable dismiss = new Runnable() {
            @Override public void run() {
                // 건너뛰어도 저장한다. 빈 값이면 댓글 알림은 꺼진 채로 시작한다.
                CommentChecker.setNickname(activity, nickname.getText().toString());
                preferences.edit().putBoolean(FeedChecker.KEY_TUTORIAL_SEEN, true).commit();
                ((ViewGroup) scrim.getParent()).removeView(scrim);
            }
        };
        skip.setOnClickListener(view -> dismiss.run());
        next.setOnClickListener(view -> {
            if (step[0] == PAGES.length - 1) {
                dismiss.run();
                return;
            }
            step[0]++;
            render.run();
        });
        art.setOnClickListener(view -> next.performClick()); // 그림을 눌러도 넘어간다
        render.run();

        // 화면이 짧으면 버튼이 밀려나는 대신 카드가 스크롤된다.
        ScrollView cardScroll = new ScrollView(activity);
        cardScroll.addView(card, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams cardParams =
                new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        cardParams.setMargins(activity.dp(22), activity.dp(22), activity.dp(22), activity.dp(22));
        scrim.addView(cardScroll, cardParams);
        activity.getWindow().addContentView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }
}
```

- [ ] **Step 3: MainActivity 에서 옛 코드 지우기**

`TUTORIAL` 배열과 `maybeShowTutorial()` 메서드 전체를 지우고, 호출부를 바꾼다.

```java
        Tutorial.maybeShow(this);
```

`maybeShowTutorial()`을 부르던 자리(`onCreate` 안)를 찾아 위 한 줄로 바꾼다. 안 쓰게 된 import(`Typeface` 등)가 남으면 `-Xlint:all -Werror`가 잡으므로 컴파일 오류를 보고 지운다.

- [ ] **Step 4: TutorialArt 에 6번째 쪽 그림 추가**

`TutorialArt.onDraw`의 `step` 분기 끝에 쪽 하나를 더한다. 앞 쪽들과 같은 방식으로 카드 안에 입력칸 하나와 커서를 그린다.

```java
        } else if (step == 5) {
            // 닉네임 입력칸 한 줄과 그 위의 라벨.
            fill.setColor(0x22000000);
            box.set(w * 0.18f, h * 0.34f, w * 0.82f, h * 0.42f);
            canvas.drawRoundRect(box, unit * 4f, unit * 4f, fill);
            box.set(w * 0.18f, h * 0.48f, w * 0.82f, h * 0.62f);
            stroke.setPathEffect(null);
            canvas.drawRoundRect(box, unit * 6f, unit * 6f, stroke);
            canvas.drawLine(w * 0.24f, h * 0.52f, w * 0.24f, h * 0.58f, stroke);
        }
```

`TutorialArt`가 쓰는 지역 변수 이름(`w`, `h`, `unit`)이 실제 코드와 다르면 그 파일의 이름을 따른다. 이 그림은 장식이므로 정확한 좌표보다 앞 쪽들과 톤이 맞는 것이 중요하다.

- [ ] **Step 5: 빌드 확인**

Run: `./build-android.ps1`
Expected: 앱 컴파일 통과

- [ ] **Step 6: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/Tutorial.java \
        IllusionLiveNotifier.Android/src/com/illusionlive/notifier/MainActivity.java \
        IllusionLiveNotifier.Android/src/com/illusionlive/notifier/TutorialArt.java
git commit -m "feat: ask for the nickname during the first-run walkthrough"
```

---

### Task 10: 설정 화면 카드

**Files:**
- Modify: `IllusionLiveNotifier.Android/src/com/illusionlive/notifier/MainActivity.java:519-541`

**Interfaces:**
- Consumes: `CommentChecker` 상수와 접근자
- Produces: 없음

- [ ] **Step 1: 카드 추가**

`showSettings()`에서 `pane.addView(backgroundCard, ...)` 바로 뒤, `pane.addView(sectionTitle("게시판별 알림"), ...)` 앞에 넣는다.

```java
        pane.addView(commentCard(preferences), matchWrap(dp(11)));
```

메서드는 `addBoardRow` 옆에 둔다.

```java
    /** 닉네임 한 줄과 스위치 두 개. 닉네임이 비어 있으면 스위치는 꺼진 채 흐리게 보인다. */
    private LinearLayout commentCard(final SharedPreferences preferences) {
        LinearLayout card = card();
        final String nickname = CommentChecker.nickname(this);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackground(ripple(SURFACE, 0, MUTED));
        TextView label = text("닉네임", 15);
        TextView value = text(nickname.isEmpty() ? "설정 안 함" : nickname, 15);
        value.setTextColor(nickname.isEmpty() ? MUTED : FAINT);
        value.setGravity(Gravity.END);
        row.addView(label, new LinearLayout.LayoutParams(-2, -2));
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { askNickname(); }
        });
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = text("사이트에서 쓰는 닉네임과 정확히 같아야 합니다.", 12.5f);
        hint.setTextColor(MUTED);
        hint.setPadding(dp(14), 0, dp(14), dp(10));
        card.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        card.addView(commentSwitch(preferences, "내 글에 달린 댓글",
                CommentChecker.KEY_MY_POSTS, !nickname.isEmpty()),
                new LinearLayout.LayoutParams(-1, -2));
        card.addView(commentSwitch(preferences, "내 댓글에 달린 답",
                CommentChecker.KEY_MY_REPLIES, !nickname.isEmpty()),
                new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private Switch commentSwitch(final SharedPreferences preferences, String label,
                                 final String key, boolean usable) {
        Switch row = switchRow(label, 14, usable && preferences.getBoolean(key, false),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton view, boolean checked) {
                        preferences.edit().putBoolean(key, checked).commit();
                        if (checked) {
                            CommentChecker.ensureNotificationChannel(MainActivity.this);
                            requestNotificationPermission();
                        }
                    }
                });
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setEnabled(usable);
        row.setAlpha(usable ? 1f : 0.4f);
        return row;
    }
```

- [ ] **Step 2: 닉네임 다이얼로그**

```java
    /**
     * 닉네임을 받는다. 최근 글과 이미 받아 둔 댓글 작성자 중에 없으면 한 번 되묻는다 —
     * 오타를 그대로 저장하면 알림이 영영 오지 않고 원인도 드러나지 않는다. 되묻기만 하고 막지는
     * 않는다. 글을 한 번도 쓰지 않은 사람은 목록에 없는 게 정상이기 때문이다.
     */
    private void askNickname() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(CommentChecker.nickname(this));
        input.setSelection(input.getText().length());
        int pad = dp(20);
        input.setPadding(pad, dp(12), pad, dp(12));

        new AlertDialog.Builder(this)
                .setTitle("닉네임")
                .setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        final String value = input.getText().toString().trim();
                        if (value.isEmpty() || knownAuthor(value)) {
                            CommentChecker.setNickname(MainActivity.this, value);
                            showSettings();
                            return;
                        }
                        new AlertDialog.Builder(MainActivity.this)
                                .setMessage("최근 글에서 '" + value + "' 을(를) 찾지 못했습니다.\n그대로 저장할까요?")
                                .setNegativeButton("다시 입력", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) { askNickname(); }
                                })
                                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        CommentChecker.setNickname(MainActivity.this, value);
                                        showSettings();
                                    }
                                })
                                .show();
                    }
                })
                .show();
    }

    /** 캐시된 글 작성자 중에 있는지. 네트워크를 쓰지 않는다. */
    private boolean knownAuthor(String nickname) {
        for (FeedParser.Post post : FeedChecker.cachedPosts(this)) {
            if (nickname.equals(post.author)) return true;
        }
        return false;
    }
```

import에 `android.app.AlertDialog`, `android.content.DialogInterface`, `android.text.InputType`, `android.widget.EditText`를 추가한다.

- [ ] **Step 3: 빌드 확인**

Run: `./build-android.ps1`
Expected: 앱 컴파일 통과

- [ ] **Step 4: 기기에서 손으로 확인**

APK를 설치하고 순서대로 본다.

1. 앱 데이터를 지우고 첫 실행 — 안내 6번째 쪽에 닉네임 칸이 보인다
2. 비워 두고 `시작하기` — 설정에 "설정 안 함", 스위치 두 개가 흐리고 꺼져 있다
3. 닉네임 행을 눌러 사이트에 없는 값을 넣고 저장 — "찾지 못했습니다" 확인 창이 뜬다
4. 실제 닉네임으로 저장 — 스위치 두 개가 저절로 켜진다
5. 당겨서 새로고침 두 번 — 첫 번째는 기준점만 잡으므로 알림이 없어야 한다
6. 내 글에 다른 계정으로 댓글을 달고 새로고침 — 5분 안에 댓글 알림이 온다
7. 닉네임을 다른 값으로 바꾸고 새로고침 — 이전 추적이 지워져 알림이 다시 기준점부터 시작한다

- [ ] **Step 5: 커밋**

```bash
git add IllusionLiveNotifier.Android/src/com/illusionlive/notifier/MainActivity.java
git commit -m "feat: add the comment-notification settings card"
```

---

### Task 11: 문서와 버전

**Files:**
- Modify: `README.md`
- Modify: `build-android.ps1:64`

**Interfaces:**
- Consumes: 없음
- Produces: 없음

- [ ] **Step 1: README 기능 항목 추가**

`## 기능` 목록에 두 줄을 넣는다.

```markdown
- 내 글에 달린 댓글 알림 (닉네임을 입력한 경우)
- 내 댓글에 달린 답 알림
```

`## 데이터` 항목을 바꾼다. 읽는 주소가 늘었으므로 정확히 적는다.

```markdown
## 데이터

- 읽기: `https://www.illusionlive.com/rss`
- 댓글 알림을 켜면 추가로: 글 페이지(`?bmode=view&idx=…`)와 `https://www.illusionlive.com/ajax/post_comment_paging.cm`
- 설정은 기기 안에만 저장
- 별도 서버, 계정 수집, 추적 없음
- 사이트에 로그인하지 않습니다. 닉네임은 내 글과 내 댓글을 알아보는 데만 쓰이며 기기를 떠나지 않습니다
```

마지막 문단에 한 줄 더한다.

```markdown
댓글 알림은 최근 글 약 50개(나흘치) 안에서만 동작합니다. 그보다 오래된 글에 달린 댓글은 감지하지 않습니다.
```

- [ ] **Step 2: 버전 올리기**

`build-android.ps1` 64번 줄:

```powershell
    --min-sdk-version 26 --target-sdk-version 36 --version-code 14 --version-name 1.1.0 `
```

- [ ] **Step 3: 전체 빌드와 자체 테스트**

```powershell
$env:ILLUSIONLIVE_KEYSTORE_PASSWORD = '<서명 키 비밀번호>'
./build-android.ps1
```

Expected: `SELF-TEST PASS` 출력 후 `android-dist/IllusionLiveNotifier.apk` 생성

- [ ] **Step 4: APK 를 다운로드 폴더에 압축**

```powershell
$dl = Join-Path $env:USERPROFILE 'Downloads'
$ver = (Select-String -Path build-android.ps1 -Pattern '--version-name\s+(\S+)').Matches[0].Groups[1].Value
Compress-Archive -Path 'android-dist\IllusionLiveNotifier.apk' -DestinationPath (Join-Path $dl "IllusionLiveNotifier-android-v$ver.zip") -CompressionLevel Optimal -Force
Get-FileHash (Join-Path $dl "IllusionLiveNotifier-android-v$ver.zip") -Algorithm SHA256
```

zip 경로와 SHA256을 사용자에게 알린다.

- [ ] **Step 5: 커밋**

```bash
git add README.md build-android.ps1
git commit -m "docs: describe comment notifications and bump to 1.1.0"
```

---

### Task 12: PR

**Files:** 없음

- [ ] **Step 1: 브랜치가 origin/main 위에 선형인지 확인**

```powershell
git fetch origin
git merge-base --is-ancestor origin/main HEAD
```

실패하면 `git rebase origin/main`.

- [ ] **Step 2: 푸시**

```bash
git push -u origin feat/comment-notifications
```

- [ ] **Step 3: PR 생성**

```bash
gh pr create --base main --title "feat: 댓글 알림" --body "..."
```

본문에 담을 것:

- 무엇이 추가됐는지 (스위치 두 개, 닉네임 입력)
- 설계 문서 링크 `docs/superpowers/specs/2026-09-12-comment-notifications-design.md`
- 검증 방법: `./test-android.ps1`, `./build-android.ps1`, Task 10의 수동 확인 7단계
- 알려진 한계: RSS 창 밖 글, 동명이인, 답변 대상 추정 오차
- 데이터 사용량: 재확인 최악치 16 MB/일, 발견 스캔 750 KB/일

---

## 자가 점검

**설계 문서 대비 빠진 항목:** 없음. 설계의 각 절이 Task 1~11에 하나씩 대응한다. 신원 → Task 5·10, 저장 상태 → Task 3·5, 흐름 → Task 6·8, 알림 규칙 → Task 4, 비용 상한 → Task 6, UI → Task 9·10, 첫 시딩 → Task 6, 파일 구성 → 전체, 파싱·보안 → Task 1·2·3·7, 테스트 → Task 1~4, 버전 → Task 11.

**이름 일치 확인:** `CommentParser.parseComments`·`postCode`·`boardCode`, `TrackedPosts.add`·`prune`·`due`·`markChecked`·`encode`·`decode`·`MY_POST`·`MY_COMMENT`·`MAX`·`TTL_MS`, `CommentRules.pick`, `CommentChecker.run`·`nickname`·`setNickname`·`enabled`·`ensureNotificationChannel`·`KEY_*`, `Tutorial.maybeShow` — 정의한 곳과 쓰는 곳의 철자가 모두 같다.

**남은 판단 하나**

- `TutorialArt`의 6번째 쪽 그림은 좌표가 그 파일의 기존 지역 변수 이름에 달려 있다. 이름이 다르면 파일을 따른다. 장식이라 정확한 좌표보다 앞 쪽들과 톤이 맞는 것이 중요하다.
