package com.illusionlive.notifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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

        System.out.println("SELF-TEST PASS");
    }
}
