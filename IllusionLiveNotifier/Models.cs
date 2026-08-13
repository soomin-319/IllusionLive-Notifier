using System.Text.Json;
using System.Text.Json.Serialization;

namespace IllusionLiveNotifier;

internal sealed record BoardInfo(string Slug, string Name, string Group, bool EnabledByDefault = true);

internal sealed record FeedPost(string Id, string BoardSlug, string Title, string Author, DateTimeOffset Published, string Url);

internal sealed class BoardSetting
{
    public bool Enabled { get; set; } = true;
    public string Keywords { get; set; } = "";

    public bool Matches(FeedPost post)
    {
        if (!Enabled) return false;
        var words = Keywords.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return words.Length == 0 || words.Any(word =>
            post.Title.Contains(word, StringComparison.CurrentCultureIgnoreCase) ||
            post.Author.Contains(word, StringComparison.CurrentCultureIgnoreCase));
    }
}

internal sealed class AppSettings
{
    public bool StartWithWindows { get; set; }
    public bool Initialized { get; set; }
    public Dictionary<string, BoardSetting> Boards { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public List<string> SeenIds { get; set; } = [];

    /// Last fetch's posts, kept so the list still shows up without a connection.
    public List<FeedPost> CachedPosts { get; set; } = [];

    public BoardSetting Board(string slug)
    {
        if (Boards.TryGetValue(slug, out var setting)) return setting;
        var known = KnownBoards.All.FirstOrDefault(x => x.Slug.Equals(slug, StringComparison.OrdinalIgnoreCase));
        return Boards[slug] = new BoardSetting { Enabled = known?.EnabledByDefault ?? true };
    }
}

internal static class KnownBoards
{
    public static readonly BoardInfo[] All =
    [
        new("newspage", "최신 소식", "공식"),
        new("IL_Community", "통합 커뮤니티", "공식"),

        new("82", "냐루네 공지", "냐루"),
        new("community_nyaru", "냐뭄 놀이터", "냐루"),
        new("99", "선물 수여식", "냐루"),

        new("chung01", "예소리 이야기", "청예솔"),
        new("chung02", "이야기 주머니", "청예솔"),
        new("chung03", "썰풀이 마당", "청예솔"),

        new("seomong", "서몽 게시판", "서몽"),
        new("65", "TEST", "서몽", false),
        new("community_rusticana", "루스티카나 커뮤니티", "루스티카나"),
        new("community_fuyuno", "후유노의 겨울카페", "후유노"),

        new("LEEE1", "리이가 말한다!", "리이"),
        new("LEEE2", "리이리와", "리이"),
        new("LEEE3", "리이야 이거해줘!", "리이"),

        new("community_rom", "부적단 이야기", "디롬"),
        new("75", "늑대네 공지", "디롬"),
        new("80", "To.디롬", "디롬"),
        new("77", "팬작업물", "디롬"),
        new("78", "비상업용 커미션", "디롬"),
        new("79", "상업용 커미션", "디롬"),

        new("sohee1", "소히가 할말있어", "소히"),
        new("sohee2", "소랑이가 할말있어", "소히"),
        new("88", "그루에게", "쿠모리 키피"),
        new("community_keepie", "키피에게", "쿠모리 키피"),

        new("eb", "이비의 방", "이비 EB"),
        new("eb_notice", "이비네 공지", "이비 EB"),
        new("eb_diary", "이비의 일기", "이비 EB"),
        new("bb", "비비단의 방", "비비"),
        new("bb_free", "비비 놀이터", "비비"),
        new("bb_game", "추천과 팁", "비비"),

        new("comet_001", "공지", "코메"),
        new("comet_002", "선장일지", "코메"),
        new("119", "보상전달", "코메"),
        new("comet_003", "뜽어일지", "코메"),
        new("comet_004", "선장 이거봐!", "코메"),

        new("124", "사서님 서한", "위즐리어카"),
        new("125", "꼬슴이 우편", "위즐리어카"),

        new("128", "공지", "도라리"),
        new("129", "잡담", "도라리"),
        new("130", "선원 잡담", "도라리"),
        new("131", "이거 해줘", "도라리"),

        new("133", "공지", "유메루"),
        new("134", "방종후기 / 잡담", "유메루"),
        new("135", "꿈몽쓰의 공간", "유메루"),
        new("136", "게임추천 / 팁 / 정보", "유메루"),
        new("137", "메루야 이거 봐라", "유메루")
    ];

    public static BoardInfo Find(string slug) =>
        All.FirstOrDefault(x => x.Slug.Equals(slug, StringComparison.OrdinalIgnoreCase))
        ?? new BoardInfo(slug, slug, "새 게시판");
}

internal static class SettingsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public static string DirectoryPath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "IllusionLiveNotifier");
    public static string FilePath => Path.Combine(DirectoryPath, "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return Normalize(JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(FilePath), JsonOptions) ?? new());
        }
        catch (Exception ex)
        {
            TryBackupBrokenFile();
            MessageBox.Show($"설정 파일을 읽지 못해 기본값으로 시작합니다.\n\n{ex.Message}",
                "설정 복구", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }

        return Normalize(new AppSettings());
    }

    public static void Save(AppSettings settings)
    {
        Directory.CreateDirectory(DirectoryPath);
        var temp = FilePath + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(settings, JsonOptions));
        File.Move(temp, FilePath, true);
    }

    private static AppSettings Normalize(AppSettings settings)
    {
        settings.Boards = new Dictionary<string, BoardSetting>(settings.Boards ?? [], StringComparer.OrdinalIgnoreCase);
        settings.SeenIds ??= [];
        settings.CachedPosts ??= [];
        foreach (var board in KnownBoards.All) settings.Board(board.Slug);
        return settings;
    }

    private static void TryBackupBrokenFile()
    {
        try
        {
            if (File.Exists(FilePath)) File.Move(FilePath, FilePath + $".broken-{DateTime.Now:yyyyMMddHHmmss}");
        }
        catch { }
    }
}
