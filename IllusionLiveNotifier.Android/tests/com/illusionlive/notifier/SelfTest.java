package com.illusionlive.notifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SelfTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--file".equals(args[0])) {
            List<FeedParser.Post> live = FeedParser.parse(Files.readAllBytes(Paths.get(args[1])));
            assert !live.isEmpty() : "Live feed contains no board posts";
            System.out.println("LIVE CHECK PASS: " + live.size() + " posts, latest=" +
                    live.get(0).boardSlug + "/" + live.get(0).title);
            return;
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel>" +
                "<item><title>왕크&amp;왕귀</title><author>코메</author>" +
                "<link>https://www.illusionlive.com/comet_004?bmode=view&amp;idx=7</link>" +
                "<guid>post-7</guid><pubDate>Tue, 11 Aug 2026 19:00:00 +0900</pubDate></item>" +
                "<item><title>상품</title><link>https://www.illusionlive.com/shop_view?id=1</link></item>" +
                "</channel></rss>";
        List<FeedParser.Post> posts = FeedParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        assert posts.size() == 1 : "Only board posts must remain";
        FeedParser.Post post = posts.get(0);
        assert "post-7".equals(post.id);
        assert "comet_004".equals(post.boardSlug);
        assert "왕크&왕귀".equals(post.title);
        assert "코메".equals(post.author);
        assert post.url.startsWith("https://www.illusionlive.com/comet_004");

        // The device parser ignores disallow-doctype-decl, so the byte scan has to catch the
        // declaration on its own — in whichever encoding the response arrives.
        String doctype = "<?xml version=\"1.0\"?><!DOCTYPE rss [" +
                "<!ENTITY x SYSTEM \"file:///etc/hosts\">]><rss/>";
        assert FeedParser.hasDoctype(doctype.getBytes(StandardCharsets.UTF_8)) : "UTF-8 DOCTYPE caught";
        assert FeedParser.hasDoctype(doctype.getBytes(StandardCharsets.UTF_16LE)) : "UTF-16LE DOCTYPE caught";
        assert FeedParser.hasDoctype(doctype.getBytes(StandardCharsets.UTF_16BE)) : "UTF-16BE DOCTYPE caught";
        assert !FeedParser.hasDoctype(xml.getBytes(StandardCharsets.UTF_8)) : "A plain feed is not a DOCTYPE";
        assert FeedParser.hasDoctype(((char) 0xfeff + doctype).getBytes(StandardCharsets.UTF_8))
                : "A byte-order mark does not hide the declaration";
        assert FeedParser.hasDoctype("<!-- c --><?pi?><!DOCTYPE rss><rss/>".getBytes(StandardCharsets.UTF_8))
                : "A declaration behind a comment is still in the prolog";

        // ...but the scan stops at the root element, so a post that only quotes the string parses.
        String cdata = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><!-- <!DOCTYPE here is text --><rss><channel>" +
                "<item><title><![CDATA[<!DOCTYPE html> explained]]></title><guid>post-9</guid>" +
                "<link>https://www.illusionlive.com/eb?bmode=view&amp;idx=9</link></item>" +
                "</channel></rss>";
        assert !FeedParser.hasDoctype(cdata.getBytes(StandardCharsets.UTF_8)) : "Quoted text is not a DTD";
        assert FeedParser.parse(cdata.getBytes(StandardCharsets.UTF_8)).size() == 1 : "The CDATA post survives";

        // URI.getPath() percent-decodes, so a link can smuggle the cache separators into the slug.
        StringBuilder overlong = new StringBuilder();
        while (overlong.length() <= 64) overlong.append('a');
        String hostile = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel>" +
                "<item><title>주입</title><guid>x1</guid>" +
                "<link>https://www.illusionlive.com/ev%1Fil%1Ex?bmode=view</link></item>" +
                "<item><title>긴 슬러그</title><guid>x2</guid>" +
                "<link>https://www.illusionlive.com/" + overlong + "?bmode=view</link></item>" +
                "<item><title>정상</title><guid>x3</guid>" +
                "<link>https://www.illusionlive.com/eb?bmode=view</link></item>" +
                "</channel></rss>";
        List<FeedParser.Post> guarded = FeedParser.parse(hostile.getBytes(StandardCharsets.UTF_8));
        assert guarded.size() == 1 : "A slug carrying a separator or padding the board list is dropped";
        assert "eb".equals(guarded.get(0).boardSlug) : "The post beside it is untouched";
        assert FeedParser.decode(FeedParser.encode(guarded)).size() == 1 : "So the round-trip loses nothing";

        String unit = String.valueOf((char) 0x1f);
        assert FeedParser.decode("x" + unit + "eb" + unit + "제목" + unit + "이비" + unit + "1000"
                + unit + "javascript:alert(1)").isEmpty()
                : "A stored row that is not https never reaches ACTION_VIEW";

        FeedParser.Post older = new FeedParser.Post("a", "eb", "옛 글", "이비", 1000L,
                "https://www.illusionlive.com/eb?bmode=view&idx=1");
        FeedParser.Post stale = new FeedParser.Post("a", "eb", "옛 제목", "이비", 1000L, older.url);
        FeedParser.Post newer = new FeedParser.Post("b", "eb", "새 글", "이비", 2000L,
                "https://www.illusionlive.com/eb?bmode=view&idx=2");
        List<FeedParser.Post> merged = FeedParser.merge(Arrays.asList(newer, older),
                Arrays.asList(stale), 200);
        assert merged.size() == 2 : "Cached duplicates collapse by id";
        assert "b".equals(merged.get(0).id) : "New posts go on top";
        assert "옛 글".equals(merged.get(1).title) : "Fetched copy wins over the cached one";
        assert FeedParser.merge(Arrays.asList(newer, older),
                Collections.<FeedParser.Post>emptyList(), 1).size() == 1 : "Cache is capped";

        List<FeedParser.Post> restored = FeedParser.decode(FeedParser.encode(merged));
        assert restored.size() == 2 : "Cache round-trip keeps every post";
        assert "새 글".equals(restored.get(0).title) && restored.get(0).published == 2000L
                && newer.url.equals(restored.get(0).url) : "Cache round-trip keeps every field";
        assert FeedParser.decode("").isEmpty() : "Empty cache decodes to nothing";

        assert MemberColors.of("코메")[0] == 0xFF413C40 : "Member colour lookup";
        assert MemberColors.of("도라리").length == 2 : "Two-colour member keeps both";
        assert MemberColors.of("공식") == null : "Unlisted group keeps the neutral chip";
        assert MemberColors.readableOn(0xFFFFFFFF, 0xFF413C40) == 0xFF413C40 : "Dark member keeps its colour";
        int pale = MemberColors.readableOn(0xFFFFFFFF, 0xFFFDF3EA);
        assert pale != 0xFFFDF3EA : "Near-white member is darkened to stay readable";
        assert MemberColors.readableOn(0xFFFFFFFF, pale) == pale : "Adjustment is stable";
        int onDark = MemberColors.readableOn(0xFF101119, 0xFF413C40);
        assert ((onDark >> 8) & 0xFF) > 0x3C : "Dark member is lifted on the dark canvas";
        assert MemberColors.readableOn(0xFF101119, onDark) == onDark : "Dark-canvas result is stable";
        assert MemberColors.readableOn(0xFF101119, 0xFFFDF3EA) == 0xFFFDF3EA
                : "Pale member already reads on the dark canvas";

        // The settings band fills with the member's colour untouched and only picks the ink.
        assert MemberColors.inkOn(0xFFFDF3EA) == 0xFF15161C : "Near-white band takes dark ink";
        assert MemberColors.inkOn(0xFF9F1D4C) == 0xFFFFFFFF : "Deep band takes white ink";
        assert MemberColors.inkOn(0xFF353958) == 0xFFFFFFFF : "The brand band takes white ink";
        for (String group : new String[]{"유메루", "도라리", "위즐리어카", "코메", "이비 EB",
                "쿠모리 키피", "소히", "디롬", "리이", "후유노", "루스티카나", "서몽", "청예솔", "냐루"}) {
            int fill = MemberColors.of(group)[0];
            assert MemberColors.readableOn(fill, MemberColors.inkOn(fill)) == MemberColors.inkOn(fill)
                    : "Band ink already clears 4.5:1 for " + group;
        }
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

        // 중첩 댓글(답글): _sub_comment_wrap 안에 완전한 comment 블록이 하나 더 있다.
        String nestedHtml =
                "<div class=\"comment\" id=\"c2026081011a2b3c4d5e6f\">" +
                "<div class=\" main_comment _comment_wrap _comment_wrap_c2026081011a2b3c4d5e6f\">" +
                "<div class=\"write\">원글작성자<span class=\"_comment_at_nick tag date\">2026-08-10 11:00</span></div>" +
                "<span class=\"_comment_body_m20260818c51a24881609d  _comment_body_c2026081011a2b3c4d5e6f\"" +
                " comment_body_code=\"c2026081011a2b3c4d5e6f\">원 댓글이에요</span>" +
                "</div>" +
                "<div class=\"dropdown_comment _sub_comment_wrap sub_comment_wrap\">" +
                "<div class=\"comment\" id=\"c2026081011f6e5d4c3b2a\">" +
                "<div class=\" main_comment _comment_wrap _comment_wrap_c2026081011f6e5d4c3b2a\">" +
                "<div class=\"write\">답글작성자<span class=\"_comment_at_nick tag date\">2026-08-10 11:05</span></div>" +
                "<span class=\"_comment_body_m2026020341fbaa000ea22  _comment_body_c2026081011f6e5d4c3b2a\"" +
                " comment_body_code=\"c2026081011f6e5d4c3b2a\">답글이에요</span>" +
                "</div></div>" +
                "</div>" +
                "</div>";

        List<CommentParser.Comment> nested = CommentParser.parseComments(nestedHtml);
        assert nested.size() == 2 : "중첩된 답글도 함께 수집된다";
        assert "c2026081011a2b3c4d5e6f".equals(nested.get(0).code) : "부모 댓글이 먼저 온다";
        assert "원글작성자".equals(nested.get(0).author) : "부모 댓글의 작성자";
        assert "m20260818c51a24881609d".equals(nested.get(0).member) : "부모 댓글의 회원 코드";
        assert "원 댓글이에요".equals(nested.get(0).body) : "부모 댓글의 본문";
        assert "c2026081011f6e5d4c3b2a".equals(nested.get(1).code) : "중첩된 답글이 그 다음에 온다";
        assert "답글작성자".equals(nested.get(1).author) : "답글의 작성자";
        assert "m2026020341fbaa000ea22".equals(nested.get(1).member) : "답글의 회원 코드";
        assert "답글이에요".equals(nested.get(1).body) : "답글의 본문";

        // 본문이 길어도(2000자를 넘어도) tools 앞에서 제대로 닫히면 댓글은 버려지지 않는다.
        StringBuilder longBody = new StringBuilder();
        while (longBody.length() <= 2500) longBody.append('a');
        String longBodyHtml =
                "<div class=\"comment\" id=\"c2026081300a0a0a0a0a0a\">" +
                "<div class=\" main_comment _comment_wrap _comment_wrap_c2026081300a0a0a0a0a0a\">" +
                "<div class=\"write\">긴글쓴이<span class=\"_comment_at_nick tag date\">2026-08-13 00:00</span></div>" +
                "<span class=\"_comment_body_m20260818c51a24881609d  _comment_body_c2026081300a0a0a0a0a0a\"" +
                " comment_body_code=\"c2026081300a0a0a0a0a0a\">" + longBody + "</span>" +
                "<div class=\"tools clearfix\"><a href=\"#\" class=\"btn_reply\">답글</a><a href=\"#\" class=\"btn_report\">신고</a></div>" +
                "</div></div>";
        List<CommentParser.Comment> longComments = CommentParser.parseComments(longBodyHtml);
        assert longComments.size() == 1 : "2500자 본문이라도 댓글 자체는 버려지지 않는다";
        assert longComments.get(0).body.length() == 120
                : "본문은 버려지지 않되 120자로만 잘린다: [" + longComments.get(0).body.length() + "]";

        // 본문 span 이 안 닫히면 tools 뒤 페이저 숫자가 섞여 들어오지 않고 그 댓글을 버린다.
        String unclosedHtml =
                "<div class=\"comment\" id=\"c2026081400a0a0a0a0a0a\">" +
                "<div class=\" main_comment _comment_wrap _comment_wrap_c2026081400a0a0a0a0a0a\">" +
                "<div class=\"write\">미확인<span class=\"_comment_at_nick tag date\">2026-08-14 00:00</span></div>" +
                "<span class=\"_comment_body_m20260818c51a24881609d  _comment_body_c2026081400a0a0a0a0a0a\"" +
                " comment_body_code=\"c2026081400a0a0a0a0a0a\">짧은 댓글" +
                "<div class=\"tools clearfix\"><a href=\"#\" class=\"btn_reply\">답글</a><a href=\"#\" class=\"btn_report\">신고</a></div>" +
                "</div></div>" +
                "<div class=\"paging\"><a href=\"#\" class=\"prev\">이전</a><span class=\"num\">1</span> <span class=\"num on\">2</span></div>";
        assert CommentParser.parseComments(unclosedHtml).isEmpty()
                : "본문이 안 닫히면 tools 뒤 페이저 마크업을 삼키지 않고 버린다";

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

        // 댓글 폼 밖의 decoy 는 무시하고 폼 안의 정상 코드를 써야 한다
        String pageWithDecoy =
                "<div class=\"comment_textarea\">" +
                "<input type=\"hidden\" name=\"post_code\" value=\"p20260101aaaaaaaaaaaaa\">" +
                "<form id=\"comment_form\">" +
                "<input type=\"hidden\" name=\"post_code\" value=\"p20260809213e43a4f8ac7\">" +
                "<input type=\"hidden\" name=\"board_code\" value=\"b20260808bcd2709bb86e5\">" +
                "</form></div>";
        assert "p20260809213e43a4f8ac7".equals(CommentParser.postCode(pageWithDecoy))
                : "폼 안의 코드를 써야 decoy 가 아니다";
        assert "b20260808bcd2709bb86e5".equals(CommentParser.boardCode(pageWithDecoy))
                : "폼 안의 코드를 써야 decoy 가 아니다";

        // 댓글 폼이 없으면 코드가 있어도 빈 문자열을 돌려야 한다
        String pageWithoutForm =
                "<div><input type=\"hidden\" name=\"post_code\" value=\"p20260809213e43a4f8ac7\">" +
                "<input type=\"hidden\" name=\"board_code\" value=\"b20260808bcd2709bb86e5\"></div>";
        assert CommentParser.postCode(pageWithoutForm).isEmpty()
                : "댓글 폼이 없으면 신뢰할 수 없다";
        assert CommentParser.boardCode(pageWithoutForm).isEmpty()
                : "댓글 폼이 없으면 신뢰할 수 없다";

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
        assert decoded.get(0).postCode.equals(many.get(0).postCode);
        assert decoded.get(0).boardCode.equals(many.get(0).boardCode);
        assert decoded.get(0).added == many.get(0).added;
        assert decoded.get(0).checked == many.get(0).checked;
        assert decoded.get(0).reason == many.get(0).reason;
        assert TrackedPosts.decode("").isEmpty();
        assert TrackedPosts.decode("깨진줄").isEmpty() : "필드 수가 안 맞으면 그 줄은 버린다";

        // 저장된 코드의 접두 문자도 검사한다. 필드 순서를 바꾸면 걸린다.
        char UNIT = 0x1f;
        char RECORD = 0x1e;
        String swappedCodes = "https://www.illusionlive.com/eb?idx=5" + UNIT
                + "b2026090300000000000b1" + UNIT  // postCode 자리에 b-접두 코드
                + "p2026090300000000000a1" + UNIT  // boardCode 자리에 p-접두 코드
                + now + UNIT + "0" + UNIT + "1";
        assert TrackedPosts.decode(swappedCodes).isEmpty()
                : "postCode/boardCode 가 뒤바뀐 저장 행은 버린다";

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

        // ---------------------------------------------------------- 재스캔 후보
        // 발견 스캔은 RSS 를 새 글부터 연다. added 에 게시 시각을 넣으므로 오래된 글이 나중에
        // 들어와도 상한은 게시가 가장 이른 글부터 자른다.
        List<TrackedPosts.Tracked> candidates = new ArrayList<>();
        for (int i = 24; i >= 0; i--) {
            candidates = TrackedPosts.addCandidate(candidates, new TrackedPosts.Tracked(
                    "https://www.illusionlive.com/eb?idx=" + i,
                    "p2026090300000000000a1", "b2026090300000000000b1",
                    now - day + i, now, 0), now);
        }
        assert candidates.size() == TrackedPosts.MAX
                : "후보 상한 " + TrackedPosts.MAX + ", 실제 " + candidates.size();
        assert candidates.get(0).url.endsWith("idx=24") : "게시가 가장 최근인 글이 앞";
        assert !TrackedPosts.contains(candidates, "https://www.illusionlive.com/eb?idx=4")
                : "게시가 가장 이른 글부터 잘린다";
        assert TrackedPosts.contains(candidates, "https://www.illusionlive.com/eb?idx=5");

        // 늦게 들어와도 게시가 더 최근이면 남는다. addCandidate 가 added 를 now 로 덮으면 여기서 틀린다.
        List<TrackedPosts.Tracked> late = TrackedPosts.addCandidate(candidates,
                new TrackedPosts.Tracked("https://www.illusionlive.com/eb?idx=99",
                        "p2026090300000000000a1", "b2026090300000000000b1",
                        now - day + 30, now, 0), now);
        assert late.get(0).url.endsWith("idx=99") : "늦게 들어온 최신 글이 앞";
        assert !TrackedPosts.contains(late, "https://www.illusionlive.com/eb?idx=5")
                : "상한에서는 게시가 가장 이른 글이 빠진다";

        // 같은 url 은 한 번만 들어가고 먼저 있던 값이 남는다.
        List<TrackedPosts.Tracked> again = TrackedPosts.addCandidate(candidates,
                new TrackedPosts.Tracked("https://www.illusionlive.com/eb?idx=24",
                        "p2026090300000000000a1", "b2026090300000000000b1", now, now + 5, 0), now);
        assert again.size() == candidates.size() : "같은 url 은 늘지 않는다";
        assert again.get(0).checked == now : "먼저 있던 항목이 그대로 남는다";

        // 게시한 지 3일이 지난 글과 형식이 틀린 코드는 후보가 되지 않는다.
        assert TrackedPosts.addCandidate(new ArrayList<TrackedPosts.Tracked>(),
                new TrackedPosts.Tracked("https://www.illusionlive.com/eb?idx=30",
                        "p2026090300000000000a1", "b2026090300000000000b1",
                        now - 4 * day, now, 0), now).isEmpty()
                : "게시 3일이 지난 글은 후보가 아니다";
        assert TrackedPosts.addCandidate(new ArrayList<TrackedPosts.Tracked>(),
                new TrackedPosts.Tracked("https://www.illusionlive.com/eb?idx=31",
                        "b2026090300000000000b1", "p2026090300000000000a1", now, now, 0), now).isEmpty()
                : "postCode/boardCode 가 뒤바뀐 후보는 거부";

        // 이미 추적 중인 글은 재스캔 대상에서 빠진다.
        List<TrackedPosts.Tracked> watching = TrackedPosts.add(new ArrayList<TrackedPosts.Tracked>(),
                "https://www.illusionlive.com/eb?idx=24", "p2026090300000000000a1",
                "b2026090300000000000b1", TrackedPosts.MY_COMMENT, now);
        List<TrackedPosts.Tracked> rest = TrackedPosts.without(candidates, watching);
        assert rest.size() == candidates.size() - 1 : "추적 중인 글 하나만 빠진다";
        assert !TrackedPosts.contains(rest, "https://www.illusionlive.com/eb?idx=24");
        assert TrackedPosts.contains(rest, "https://www.illusionlive.com/eb?idx=23");

        // ---------------------------------------------------------- 보관 기준
        // 창 경계: 게시 후 TTL_MS 가 지나면 밖, 날짜를 못 읽은 글(0)은 안.
        FeedParser.Post undated = new FeedParser.Post("g-undated", "eb", "t", "a", 0,
                "https://www.illusionlive.com/eb?idx=40");
        FeedParser.Post recent = new FeedParser.Post("g-recent", "eb", "t", "a", now - day,
                "https://www.illusionlive.com/eb?idx=41");
        FeedParser.Post expired = new FeedParser.Post("g-expired", "eb", "t", "a",
                now - TrackedPosts.TTL_MS, "https://www.illusionlive.com/eb?idx=42");
        FeedParser.Post inside = new FeedParser.Post("g-inside", "eb", "t", "a",
                now - TrackedPosts.TTL_MS + 1, "https://www.illusionlive.com/eb?idx=43");
        assert !TrackedPosts.outsideWindow(undated, now) : "날짜 없는 글은 창 안";
        assert !TrackedPosts.outsideWindow(inside, now) : "TTL 직전은 창 안";
        assert TrackedPosts.outsideWindow(expired, now) : "TTL 이 지나면 창 밖";

        // scanned 는 캐시에 남아 있고 창 안인 글만 남긴다. 개수로 자르지 않는다.
        Set<String> scannedIds = new HashSet<>(Arrays.asList(
                "g-undated", "g-recent", "g-expired", "g-gone"));
        Set<String> kept = TrackedPosts.keepScanned(
                Arrays.asList(undated, recent, expired, inside), scannedIds, now);
        assert kept.equals(new HashSet<>(Arrays.asList("g-undated", "g-recent")))
                : "창 안이면서 연 적 있는 글만 남는다: " + kept;

        // seen 은 날짜가 최신인 코드부터 남긴다.
        Set<String> codes = new HashSet<>(Arrays.asList(
                "c20260901000000000000a1", "c20260914000000000000a1", "c20260910000000000000a1"));
        assert CommentRules.newestCodes(codes, 2).equals(new HashSet<>(Arrays.asList(
                "c20260914000000000000a1", "c20260910000000000000a1")))
                : "가장 오래된 코드가 빠진다";
        assert CommentRules.newestCodes(codes, 5).size() == 3 : "상한보다 적으면 전부 남는다";

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

        System.out.println("SELF-TEST PASS");
    }
}
