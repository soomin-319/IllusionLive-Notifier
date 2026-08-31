namespace IllusionLiveNotifier;

internal static class SelfTest
{
    public static int Run()
    {
        try
        {
            const string xml = """
                <?xml version="1.0" encoding="utf-8"?>
                <rss version="2.0"><channel>
                  <item><title>공지 &amp; 일정</title><link>https://www.illusionlive.com/chung02/?idx=123&amp;bmode=view</link><guid>post-123</guid><author>청예솔</author><pubDate>Tue, 11 Aug 2026 10:53:35 +0900</pubDate></item>
                  <item><title>상품</title><link>https://www.illusionlive.com/STORE/?idx=41</link><guid>product-41</guid><pubDate>Tue, 11 Aug 2026 09:00:00 +0900</pubDate></item>
                  <item><title>낚시</title><link>https://illusionlive.evil.example/chung02/?idx=9&amp;bmode=view</link><guid>evil-9</guid><pubDate>Tue, 11 Aug 2026 09:00:00 +0900</pubDate></item>
                  <item><title>평문</title><link>http://www.illusionlive.com/chung02/?idx=10&amp;bmode=view</link><guid>plain-10</guid><pubDate>Tue, 11 Aug 2026 09:00:00 +0900</pubDate></item>
                </channel></rss>
                """;

            var posts = RssFeedClient.Parse(xml);
            Assert(posts.Count == 1, "상품·외부 호스트·평문 http 항목 제외");
            Assert(posts[0].Id == "post-123" && posts[0].BoardSlug == "chung02", "글 식별");
            Assert(posts[0].Title == "공지 & 일정", "XML 엔터티 해제");

            var setting = new BoardSetting { Enabled = true, Keywords = "방송, 일정" };
            Assert(setting.Matches(posts[0]), "쉼표 키워드 OR 필터");
            setting.Keywords = "없는말";
            Assert(!setting.Matches(posts[0]), "키워드 불일치");
            setting.Enabled = false;
            setting.Keywords = "일정";
            Assert(!setting.Matches(posts[0]), "게시판 끄기");

            Assert(BoardColors.For("코메")[0] == ColorTranslator.FromHtml("#413C40"), "멤버 색상 로드");
            Assert(BoardColors.For("도라리").Length == 2, "두 색 멤버는 색상 두 개 유지");
            Assert(BoardColors.For("없는멤버").Length == 1, "미지정 멤버는 기본색");

            var now = DateTimeOffset.Parse("2026-08-12T10:00:00+09:00");
            Assert(MainForm.Relative(now.AddMinutes(-30), now) == "30분전", "상대 시각 분");
            Assert(MainForm.Relative(now.AddHours(-8), now) == "8시간전", "상대 시각 시간");
            Assert(MainForm.Relative(now.AddDays(-3), now) == "3일전", "상대 시각 일");

            var old = new FeedPost("a", "eb", "옛 글", "", now.AddDays(-2), "https://www.illusionlive.com/eb/?idx=1&bmode=view");
            var stale = old with { Title = "옛 제목" };
            var fresh = new FeedPost("b", "eb", "새 글", "", now, "https://www.illusionlive.com/eb/?idx=2&bmode=view");
            var merged = MainForm.Merge([fresh, old], [stale]);
            Assert(merged.Count == 2, "캐시 중복 제거");
            Assert(merged[0].Id == "b", "새 글이 목록 위로");
            Assert(merged[1].Title == "옛 글", "새로 받은 내용이 캐시보다 우선");

            Console.WriteLine("SELF-TEST PASS");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("SELF-TEST FAIL: " + ex.Message);
            return 1;
        }
    }

    public static async Task<int> CheckLiveAsync()
    {
        try
        {
            using var client = new RssFeedClient();
            var posts = await client.FetchAsync();
            if (posts.Count == 0) throw new InvalidOperationException("RSS에서 게시글을 찾지 못했습니다.");
            Console.WriteLine($"LIVE CHECK PASS: {posts.Count} posts, latest={posts[0].BoardSlug}/{posts[0].Title}");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("LIVE CHECK FAIL: " + ex.Message);
            return 1;
        }
    }

    private static void Assert(bool condition, string name)
    {
        if (!condition) throw new InvalidOperationException(name);
    }
}
