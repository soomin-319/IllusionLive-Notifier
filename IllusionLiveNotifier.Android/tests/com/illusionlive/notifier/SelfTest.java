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
        System.out.println("SELF-TEST PASS");
    }
}
