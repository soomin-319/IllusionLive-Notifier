using System.Text.Json;

namespace IllusionLiveNotifier;

/// Member colours come from Assets/board-colors.json so the palette can be edited without
/// touching UI code. Two colours for one member means a gradient chip.
internal static class BoardColors
{
    private static readonly Color[] Fallback = [Color.FromArgb(138, 138, 138)];
    private static readonly Palette Data = Load();

    private sealed record Palette(string[] Default, Dictionary<string, string[]> Colors);

    public static Color[] For(string group)
    {
        var hexes = Data.Colors.TryGetValue(group, out var found) ? found : Data.Default;
        var colors = hexes.Select(Parse).Where(c => c.HasValue).Select(c => c!.Value).ToArray();
        return colors.Length > 0 ? colors : Fallback;
    }

    private static Color? Parse(string hex)
    {
        try { return ColorTranslator.FromHtml(hex); }
        catch { return null; }
    }

    private static Palette Load()
    {
        try
        {
            using var stream = typeof(BoardColors).Assembly.GetManifestResourceStream("board-colors.json");
            if (stream is not null)
                return JsonSerializer.Deserialize<Palette>(stream,
                    new JsonSerializerOptions { PropertyNameCaseInsensitive = true }) ?? Empty();
        }
        catch { }
        return Empty();
    }

    private static Palette Empty() => new(["#8A8A8A"], new Dictionary<string, string[]>());
}
