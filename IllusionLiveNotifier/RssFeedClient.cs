using System.Globalization;
using System.Xml.Linq;

namespace IllusionLiveNotifier;

internal sealed class RssFeedClient : IDisposable
{
    public const string FeedUrl = "https://www.illusionlive.com/rss";
    private readonly HttpClient _http = new()
    {
        Timeout = TimeSpan.FromSeconds(20)
    };

    public RssFeedClient()
    {
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("IllusionLiveNotifier/1.0 (+https://www.illusionlive.com)");
        _http.DefaultRequestHeaders.AcceptLanguage.ParseAdd("ko-KR,ko;q=0.9");
    }

    public async Task<IReadOnlyList<FeedPost>> FetchAsync(CancellationToken cancellationToken = default)
    {
        using var response = await _http.GetAsync(FeedUrl, cancellationToken);
        response.EnsureSuccessStatusCode();
        var xml = await response.Content.ReadAsStringAsync(cancellationToken);
        return Parse(xml);
    }

    internal static IReadOnlyList<FeedPost> Parse(string xml)
    {
        var document = XDocument.Parse(xml, LoadOptions.None);
        return document.Descendants("item")
            .Select(ParseItem)
            .Where(x => x is not null)
            .Cast<FeedPost>()
            .OrderByDescending(x => x.Published)
            .ToArray();
    }

    private static FeedPost? ParseItem(XElement item)
    {
        var link = item.Element("link")?.Value.Trim() ?? "";
        if (!Uri.TryCreate(link, UriKind.Absolute, out var uri)) return null;

        var query = System.Web.HttpUtility.ParseQueryString(uri.Query);
        if (!string.Equals(query["bmode"], "view", StringComparison.OrdinalIgnoreCase)) return null;

        var board = Uri.UnescapeDataString(uri.AbsolutePath.Trim('/'));
        if (board.Length == 0) return null;

        var title = item.Element("title")?.Value.Trim() ?? "(제목 없음)";
        var author = item.Element("author")?.Value.Trim() ?? "";
        var id = item.Element("guid")?.Value.Trim();
        if (string.IsNullOrWhiteSpace(id)) id = link;

        var published = DateTimeOffset.MinValue;
        DateTimeOffset.TryParse(item.Element("pubDate")?.Value, CultureInfo.InvariantCulture,
            DateTimeStyles.AllowWhiteSpaces, out published);

        return new FeedPost(id, board, title, author, published, link);
    }

    public void Dispose() => _http.Dispose();
}
