using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using Microsoft.Win32;

namespace IllusionLiveNotifier;

internal sealed class MainForm : Form
{
    private const int PollMinutes = 5;
    private const int CacheLimit = 200;

    // Sampled from illusionlive.com: white canvas, black ink, hairline rules, no cards or
    // shadows. The brand gradient (#44B3FE → #727CFE → #9D49FE) is the only colour accent.
    private static readonly Color GradFrom = Color.FromArgb(68, 179, 254);
    private static readonly Color GradMid = Color.FromArgb(114, 124, 254);
    private static readonly Color GradTo = Color.FromArgb(157, 73, 254);
    private static readonly Color Canvas = Color.White;
    private static readonly Color Ink = Color.Black;
    private static readonly Color Muted = Color.FromArgb(118, 118, 118);
    private static readonly Color Faint = Color.FromArgb(176, 176, 176);
    private static readonly Color Line = Color.FromArgb(229, 229, 229);
    private static readonly Color Tint = Color.FromArgb(250, 250, 251);
    private static readonly Color Chip = Color.FromArgb(241, 241, 245);
    private static readonly Color Ok = Color.FromArgb(18, 183, 106);
    private static readonly Color Bad = Color.FromArgb(217, 66, 66);

    private static readonly Font MemberFont = new("맑은 고딕", 10f, FontStyle.Bold);
    private static readonly Font BoardFont = new("맑은 고딕", 9f, FontStyle.Regular);
    private static readonly Font NavOn = new("맑은 고딕", 10.5f, FontStyle.Bold);
    private static readonly Font NavOff = new("맑은 고딕", 10.5f, FontStyle.Regular);
    private static readonly StringFormat MiddleLeft = new(StringFormatFlags.NoWrap)
    {
        LineAlignment = StringAlignment.Center,
        Trimming = StringTrimming.EllipsisCharacter
    };

    private readonly AppSettings _settings = SettingsStore.Load();
    private readonly RssFeedClient _feed = new();
    private readonly CancellationTokenSource _shutdown = new();
    private readonly System.Windows.Forms.Timer _timer = new();
    private readonly bool _startHidden;

    private readonly Label _statusLabel = new();
    private readonly Label _statusDot = new();
    private readonly Label _lastCheckLabel = new();
    private readonly List<Label> _navLabels = [];
    private readonly List<Panel> _pagePanels = [];
    private readonly Button _checkButton = new();
    private readonly Button _saveButton = new();
    private readonly DataGridView _postsGrid = NewGrid();
    private readonly DataGridView _boardsGrid = NewGrid();
    private readonly CheckBox _startupCheck = new();
    private readonly NotifyIcon _trayIcon = new();

    private bool _checking;
    private bool _reallyExit;
    private string? _balloonTargetUrl;

    public MainForm(bool startHidden = false)
    {
        _startHidden = startHidden;
        Text = "일루전 라이브 알리미";
        Width = 980;
        Height = 720;
        MinimumSize = new Size(760, 560);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Canvas;
        Font = new Font("맑은 고딕", 9.5f);
        Icon = LoadAppIcon();

        BuildUi();
        RenderPosts(_settings.CachedPosts);
        LoadBoardRows();
        ConfigureTray();
        ConfigureTimer();

        Shown += async (_, _) =>
        {
            if (_startHidden)
            {
                Hide();
                ShowInTaskbar = true;
                WindowState = FormWindowState.Normal;
            }
            await CheckNowAsync();
        };
        FormClosing += OnFormClosing;
        FormClosed += (_, _) =>
        {
            _shutdown.Cancel();
            _timer.Dispose();
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _feed.Dispose();
            _shutdown.Dispose();
            Icon?.Dispose();
        };

        if (_startHidden)
        {
            ShowInTaskbar = false;
            WindowState = FormWindowState.Minimized;
        }
    }

    private void BuildUi()
    {
        var header = new Panel { Dock = DockStyle.Top, Height = 82, BackColor = Canvas, Padding = new Padding(34, 22, 34, 0) };
        var logo = new PictureBox
        {
            Image = LoadLogo(),
            SizeMode = PictureBoxSizeMode.Zoom,
            Size = new Size(260, 44),
            Location = new Point(34, 20),
            BackColor = Canvas
        };

        _saveButton.Text = "설정 저장";
        StyleHeaderButton(_saveButton, primary: false);
        _saveButton.Click += (_, _) => SaveFromUi(true);

        _checkButton.Text = "지금 확인";
        StyleHeaderButton(_checkButton, primary: true);
        _checkButton.Click += async (_, _) => await CheckNowAsync();

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Right,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            WrapContents = false,
            FlowDirection = FlowDirection.RightToLeft,
            Padding = new Padding(0, 24, 0, 0),
            BackColor = Canvas
        };
        buttons.Controls.Add(_checkButton);
        buttons.Controls.Add(_saveButton);
        header.Controls.Add(buttons);
        header.Controls.Add(logo);

        // 1px of padding top and bottom keeps the hairlines from being covered by navFlow.
        var nav = new Panel { Dock = DockStyle.Fill, Height = 44, BackColor = Canvas, Padding = new Padding(34, 1, 34, 1) };
        nav.Paint += (_, e) =>
        {
            using var pen = new Pen(Line);
            e.Graphics.DrawLine(pen, 0, 0, nav.Width, 0);
            e.Graphics.DrawLine(pen, 0, nav.Height - 1, nav.Width, nav.Height - 1);
        };
        var navFlow = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            WrapContents = false,
            FlowDirection = FlowDirection.LeftToRight,
            BackColor = Canvas,
            Padding = new Padding(0, 11, 0, 0)
        };
        nav.Controls.Add(navFlow);

        _pagePanels.Add(BuildPostsPage());
        _pagePanels.Add(BuildBoardsPage());
        _pagePanels.Add(BuildSettingsPage());

        foreach (var (text, index) in new[] { "최근 글", "게시판별 알림", "일반 설정" }.Select((t, i) => (t, i)))
        {
            var label = new Label
            {
                Text = text,
                AutoSize = true,
                Font = NavOff,
                ForeColor = Muted,
                Cursor = Cursors.Hand,
                Margin = new Padding(0, 0, 30, 0)
            };
            label.Click += (_, _) => ShowPage(index);
            _navLabels.Add(label);
            navFlow.Controls.Add(label);
        }

        var top = new Panel { Dock = DockStyle.Top, Height = 126, BackColor = Canvas };
        top.Controls.Add(nav);
        top.Controls.Add(header);

        _statusDot.Text = "●";
        _statusDot.AutoSize = true;
        _statusDot.ForeColor = Ok;
        _statusDot.Margin = new Padding(0, 2, 7, 0);
        _statusLabel.Text = "새 글 확인 준비 중";
        _statusLabel.AutoSize = true;
        _statusLabel.ForeColor = Color.FromArgb(33, 33, 33);
        _statusLabel.Margin = new Padding(0, 2, 0, 0);
        _lastCheckLabel.Text = "마지막 확인 -";
        _lastCheckLabel.AutoSize = true;
        _lastCheckLabel.ForeColor = Muted;
        _lastCheckLabel.Dock = DockStyle.Right;
        _lastCheckLabel.TextAlign = ContentAlignment.MiddleRight;

        var stateFlow = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            WrapContents = false,
            AutoSize = true,
            BackColor = Canvas,
            Padding = new Padding(0, 12, 0, 0)
        };
        stateFlow.Controls.Add(_statusDot);
        stateFlow.Controls.Add(_statusLabel);

        var state = new Panel { Dock = DockStyle.Top, Height = 40, BackColor = Canvas };
        state.Controls.Add(stateFlow);
        state.Controls.Add(_lastCheckLabel);

        var pages = new Panel { Dock = DockStyle.Fill, BackColor = Canvas };
        foreach (var page in _pagePanels) pages.Controls.Add(page);

        var body = new Panel { Dock = DockStyle.Fill, BackColor = Canvas, Padding = new Padding(34, 0, 34, 12) };
        body.Controls.Add(pages);
        body.Controls.Add(state);

        Controls.Add(body);
        Controls.Add(top);
        ShowPage(0);
    }

    private void ShowPage(int index)
    {
        for (var i = 0; i < _navLabels.Count; i++)
        {
            _navLabels[i].Font = i == index ? NavOn : NavOff;
            _navLabels[i].ForeColor = i == index ? Ink : Muted;
            _pagePanels[i].Visible = i == index;
        }
    }

    private Panel BuildPostsPage()
    {
        var page = NewPage();

        _postsGrid.ReadOnly = true;
        _postsGrid.MultiSelect = false;
        _postsGrid.ColumnHeadersVisible = false;
        _postsGrid.RowTemplate.Height = 44;
        _postsGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "게시판", Width = 262 });
        _postsGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "제목", AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill, MinimumWidth = 200 });
        _postsGrid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "작성자",
            Width = 84,
            DefaultCellStyle = new DataGridViewCellStyle { Alignment = DataGridViewContentAlignment.MiddleRight, ForeColor = Muted }
        });
        _postsGrid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "시간",
            Width = 112,
            DefaultCellStyle = new DataGridViewCellStyle
            {
                Alignment = DataGridViewContentAlignment.MiddleRight,
                ForeColor = Faint,
                Font = new Font("맑은 고딕", 9f, FontStyle.Regular)
            }
        });
        _postsGrid.CellPainting += (_, e) =>
        {
            if (e.ColumnIndex != 0 || e.RowIndex < 0) return;
            if (_postsGrid.Rows[e.RowIndex].Tag is not FeedPost post) return;
            var board = KnownBoards.Find(post.BoardSlug);
            e.PaintBackground(e.ClipBounds, true);
            PaintBoardCell(e.Graphics!, e.CellBounds, board.Group, board.Name);
            e.Handled = true;
        };
        _postsGrid.CellDoubleClick += (_, e) =>
        {
            if (e.RowIndex >= 0 && _postsGrid.Rows[e.RowIndex].Tag is FeedPost post) OpenUrl(post.Url);
        };

        page.Controls.Add(_postsGrid);
        page.Controls.Add(PageHead("최근 글", "Recent Post", "두 번 클릭하면 사이트에서 열립니다"));
        return page;
    }

    /// Member name in its own colour, then the board name in grey. Two-colour members use the first.
    private static void PaintBoardCell(Graphics g, Rectangle cell, string group, string board)
    {
        var colors = BoardColors.For(group);
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        var width = g.MeasureString(group, MemberFont).Width;
        var box = new RectangleF(cell.X + 8, cell.Y, width + 2, cell.Height);

        using (Brush brush = new SolidBrush(colors[0]))
            g.DrawString(group, MemberFont, brush, box, MiddleLeft);

        if (board.Length == 0) return;
        var rest = Rectangle.FromLTRB((int)box.Right + 9, cell.Y, cell.Right - 6, cell.Bottom);
        if (rest.Width > 12)
            TextRenderer.DrawText(g, board, BoardFont, rest, Muted,
                TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis | TextFormatFlags.Left);
    }

    private static Panel PageHead(string korean, string english, string hint)
    {
        var head = new Panel { Dock = DockStyle.Top, Height = 54, BackColor = Canvas };
        var flow = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            WrapContents = false,
            BackColor = Canvas,
            Padding = new Padding(0, 8, 0, 0)
        };
        flow.Controls.Add(new Label
        {
            Text = korean,
            AutoSize = true,
            Font = new Font("맑은 고딕", 17, FontStyle.Bold),
            ForeColor = Ink,
            Margin = new Padding(0)
        });
        flow.Controls.Add(new Label
        {
            Text = english,
            AutoSize = true,
            Font = new Font("맑은 고딕", 10.5f, FontStyle.Bold),
            ForeColor = Faint,
            Margin = new Padding(9, 12, 0, 0)
        });

        head.Controls.Add(flow);
        head.Controls.Add(new Label
        {
            Text = hint,
            AutoSize = true,
            ForeColor = Faint,
            Dock = DockStyle.Right,
            TextAlign = ContentAlignment.MiddleRight
        });
        return head;
    }

    private Panel BuildBoardsPage()
    {
        var page = NewPage();
        var top = new Panel { Dock = DockStyle.Top, Height = 46, BackColor = Canvas };
        var hint = new Label
        {
            Text = "알림 받을 게시판과 키워드를 지정하세요.",
            AutoSize = true,
            ForeColor = Muted,
            Location = new Point(2, 12)
        };
        var allOff = SmallButton("전체 끄기");
        allOff.Click += (_, _) => SetAllBoards(false);
        var allOn = SmallButton("전체 켜기");
        allOn.Click += (_, _) => SetAllBoards(true);

        // Docking the buttons directly stretches them to the panel height and clips the label.
        var actions = new FlowLayoutPanel
        {
            Dock = DockStyle.Right,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
            Padding = new Padding(0, 6, 2, 0),
            BackColor = Canvas
        };
        actions.Controls.Add(allOff);
        actions.Controls.Add(allOn);
        top.Controls.Add(actions);
        top.Controls.Add(hint);

        _boardsGrid.EditMode = DataGridViewEditMode.EditOnEnter;
        _boardsGrid.Columns.Add(new DataGridViewCheckBoxColumn { HeaderText = "알림", Width = 70 });
        _boardsGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "멤버", Width = 130, ReadOnly = true });
        _boardsGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "게시판", Width = 190, ReadOnly = true });
        _boardsGrid.Columns.Add(new DataGridViewTextBoxColumn
        {
            HeaderText = "키워드 (비워 두면 모든 글)",
            AutoSizeMode = DataGridViewAutoSizeColumnMode.Fill,
            MinimumWidth = 220
        });
        _boardsGrid.CurrentCellDirtyStateChanged += (_, _) =>
        {
            if (_boardsGrid.IsCurrentCellDirty) _boardsGrid.CommitEdit(DataGridViewDataErrorContexts.Commit);
        };
        _boardsGrid.CellPainting += (_, e) =>
        {
            if (e.ColumnIndex != 1 || e.RowIndex < 0) return;
            if (_boardsGrid.Rows[e.RowIndex].Tag is not BoardInfo board) return;
            e.PaintBackground(e.ClipBounds, true);
            PaintBoardCell(e.Graphics!, e.CellBounds, board.Group, "");
            e.Handled = true;
        };

        var bottom = new Label
        {
            Dock = DockStyle.Bottom,
            Height = 34,
            Padding = new Padding(2, 9, 0, 0),
            Text = "예: 공지, 일정  → 제목 또는 작성자에 둘 중 하나가 포함된 새 글만 알림",
            BackColor = Canvas,
            ForeColor = Muted
        };
        page.Controls.Add(_boardsGrid);
        page.Controls.Add(bottom);
        page.Controls.Add(top);
        page.Controls.Add(PageHead("게시판별 알림", "Board Alert", "저장을 눌러야 반영됩니다"));
        return page;
    }

    private Panel BuildSettingsPage()
    {
        var page = NewPage();
        page.AutoScroll = true;
        var card = new TableLayoutPanel
        {
            ColumnCount = 2,
            RowCount = 7,
            AutoSize = true,
            Location = new Point(2, 66),
            Padding = new Padding(24),
            BackColor = Canvas
        };
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 210));
        card.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 470));
        for (var i = 0; i < 7; i++) card.RowStyles.Add(new RowStyle(SizeType.Absolute, i == 5 ? 88 : 52));
        card.Paint += (_, e) =>
        {
            using var pen = new Pen(Line);
            e.Graphics.DrawRectangle(pen, 0, 0, card.Width - 1, card.Height - 1);
        };

        _startupCheck.Text = "로그인할 때 알리미 자동 실행";
        _startupCheck.Checked = _settings.StartWithWindows;
        _startupCheck.AutoSize = true;
        _startupCheck.ForeColor = Ink;
        _startupCheck.Margin = new Padding(3, 7, 0, 0);

        AddSettingRow(card, 0, "데이터 원본", ValueLabel("illusionlive.com 공식 RSS"));
        AddSettingRow(card, 1, "새 글 확인", ValueLabel("백그라운드에서 자동으로 확인합니다"));
        AddSettingRow(card, 2, "Windows 시작", _startupCheck);
        AddSettingRow(card, 3, "현재 글 처리", ValueLabel("첫 실행 때 기존 글은 알리지 않음"));

        var testButton = SmallButton("테스트 알림 보내기");
        testButton.Click += (_, _) => ShowTestNotification();
        AddSettingRow(card, 4, "알림 확인", testButton);

        var note = ValueLabel("창의 X 버튼은 앱을 종료하지 않고 알림 영역으로 숨깁니다.\n완전히 끝내려면 트레이 아이콘의 ‘종료’를 누르세요.");
        note.ForeColor = Muted;
        note.Margin = new Padding(3, 4, 0, 0);
        AddSettingRow(card, 5, "백그라운드 실행", note);

        var siteButton = SmallButton("사이트 열기");
        siteButton.Click += (_, _) => OpenUrl("https://www.illusionlive.com/");
        AddSettingRow(card, 6, "바로가기", siteButton);

        page.Controls.Add(card);
        page.Controls.Add(PageHead("일반 설정", "Settings", ""));
        return page;
    }

    private static Label ValueLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        ForeColor = Ink,
        Margin = new Padding(3, 7, 0, 0)
    };

    private void LoadBoardRows()
    {
        _boardsGrid.Rows.Clear();
        foreach (var board in KnownBoards.All)
            AddBoardRow(board);

        foreach (var slug in _settings.Boards.Keys
                     .Where(slug => !KnownBoards.All.Any(x => x.Slug.Equals(slug, StringComparison.OrdinalIgnoreCase)))
                     .OrderBy(x => x))
            AddBoardRow(KnownBoards.Find(slug));
    }

    private void AddBoardRow(BoardInfo board)
    {
        if (_boardsGrid.Rows.Cast<DataGridViewRow>().Any(row =>
                row.Tag is BoardInfo existing && existing.Slug.Equals(board.Slug, StringComparison.OrdinalIgnoreCase))) return;
        var setting = _settings.Board(board.Slug);
        var index = _boardsGrid.Rows.Add(setting.Enabled, board.Group, board.Name, setting.Keywords);
        _boardsGrid.Rows[index].Tag = board;
    }

    private async Task CheckNowAsync()
    {
        if (_checking || _shutdown.IsCancellationRequested) return;
        _checking = true;
        _checkButton.Enabled = false;
        SetStatus("새 글 확인 중…");

        try
        {
            var posts = await _feed.FetchAsync(_shutdown.Token);
            foreach (var post in posts)
            {
                var wasKnown = _settings.Boards.ContainsKey(post.BoardSlug);
                _settings.Board(post.BoardSlug);
                if (!wasKnown) AddBoardRow(KnownBoards.Find(post.BoardSlug));
            }

            _settings.CachedPosts = Merge(posts, _settings.CachedPosts);
            RenderPosts(_settings.CachedPosts);
            var seen = new HashSet<string>(_settings.SeenIds, StringComparer.Ordinal);
            var unseen = posts.Where(post => !seen.Contains(post.Id)).OrderBy(post => post.Published).ToArray();

            if (_settings.Initialized)
            {
                var matches = unseen.Where(post => _settings.Board(post.BoardSlug).Matches(post)).ToArray();
                if (matches.Length > 0) NotifyNewPosts(matches);
                SetStatus(matches.Length > 0
                    ? $"새 글 {unseen.Length}개 확인 · 알림 {matches.Length}개"
                    : $"확인 완료 · 새 글 {unseen.Length}개");
            }
            else
            {
                _settings.Initialized = true;
                SetStatus($"초기 동기화 완료 · 현재 글 {posts.Count}개 저장");
            }

            _settings.SeenIds = posts.Select(x => x.Id)
                .Concat(_settings.SeenIds)
                .Distinct(StringComparer.Ordinal)
                .Take(500)
                .ToList();
            SettingsStore.Save(_settings);
            _lastCheckLabel.Text = $"마지막 확인 {DateTime.Now:MM-dd HH:mm:ss}";
        }
        catch (OperationCanceledException) when (_shutdown.IsCancellationRequested) { }
        catch (Exception ex)
        {
            SetStatus("확인 실패 · 저장된 글 목록 표시 중 · 인터넷 연결을 확인하세요", true);
            _lastCheckLabel.Text = $"오류: {Short(ex.Message, 90)}";
        }
        finally
        {
            _checking = false;
            if (!IsDisposed) _checkButton.Enabled = true;
        }
    }

    /// Newest first, one row per id; a re-fetched post wins over its cached copy.
    public static List<FeedPost> Merge(IEnumerable<FeedPost> fresh, IEnumerable<FeedPost> cached) =>
        fresh.Concat(cached)
            .DistinctBy(post => post.Id, StringComparer.Ordinal)
            .OrderByDescending(post => post.Published)
            .Take(CacheLimit)
            .ToList();

    private void RenderPosts(IReadOnlyList<FeedPost> posts)
    {
        _postsGrid.Rows.Clear();
        foreach (var post in posts)
        {
            // Column 0 is owner-drawn from the row tag; its value stays empty on purpose.
            var index = _postsGrid.Rows.Add("", post.Title, post.Author, Relative(post.Published));
            _postsGrid.Rows[index].Tag = post;
        }
    }

    public static string Relative(DateTimeOffset when, DateTimeOffset? now = null)
    {
        if (when == DateTimeOffset.MinValue) return "-";
        var span = (now ?? DateTimeOffset.Now) - when;
        if (span.TotalSeconds < 60) return "방금";
        if (span.TotalMinutes < 60) return $"{(int)span.TotalMinutes}분전";
        if (span.TotalHours < 24) return $"{(int)span.TotalHours}시간전";
        if (span.TotalDays < 30) return $"{(int)span.TotalDays}일전";
        return when.LocalDateTime.ToString("MM-dd");
    }

    private void NotifyNewPosts(IReadOnlyList<FeedPost> posts)
    {
        if (posts.Count == 1)
        {
            var post = posts[0];
            var board = KnownBoards.Find(post.BoardSlug);
            _balloonTargetUrl = post.Url;
            _trayIcon.ShowBalloonTip(7000, $"[{board.Name}] 새 글", Short(post.Title, 180), ToolTipIcon.Info);
            return;
        }

        _balloonTargetUrl = null;
        var lines = posts.TakeLast(3).Reverse().Select(post => $"• {KnownBoards.Find(post.BoardSlug).Name}: {post.Title}");
        var more = posts.Count > 3 ? $"\n외 {posts.Count - 3}개" : "";
        _trayIcon.ShowBalloonTip(8000, $"새 글 {posts.Count}개", Short(string.Join("\n", lines) + more, 240), ToolTipIcon.Info);
    }

    private void ShowTestNotification()
    {
        _balloonTargetUrl = "https://www.illusionlive.com/";
        _trayIcon.ShowBalloonTip(7000, "일루전 라이브 알리미", "테스트 알림입니다. 알림을 누르면 사이트가 열립니다.", ToolTipIcon.Info);
        SetStatus("테스트 알림 전송 완료");
    }

    private void SaveFromUi(bool showConfirmation)
    {
        try
        {
            _boardsGrid.EndEdit();
            foreach (DataGridViewRow row in _boardsGrid.Rows)
            {
                if (row.Tag is not BoardInfo board) continue;
                var setting = _settings.Board(board.Slug);
                setting.Enabled = Convert.ToBoolean(row.Cells[0].Value ?? false);
                setting.Keywords = Convert.ToString(row.Cells[3].Value)?.Trim() ?? "";
            }

            _settings.StartWithWindows = _startupCheck.Checked;
            ApplyStartupSetting(_settings.StartWithWindows);
            SettingsStore.Save(_settings);
            if (showConfirmation) SetStatus("설정 저장 완료");
        }
        catch (Exception ex)
        {
            if (showConfirmation)
                MessageBox.Show($"설정을 저장하지 못했습니다.\n\n{ex.Message}", "저장 실패",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private void ConfigureTimer()
    {
        _timer.Interval = PollMinutes * 60 * 1000;
        _timer.Tick += TimerTick;
        _timer.Start();
    }

    private async void TimerTick(object? sender, EventArgs e) => await CheckNowAsync();

    private void ConfigureTray()
    {
        _trayIcon.Icon = Icon ?? SystemIcons.Information;
        _trayIcon.Text = "일루전 라이브 알리미";
        _trayIcon.Visible = true;
        _trayIcon.DoubleClick += (_, _) => ShowMainWindow();
        _trayIcon.BalloonTipClicked += (_, _) =>
        {
            if (_balloonTargetUrl is { } url) OpenUrl(url);
            else ShowMainWindow();
        };

        var menu = new ContextMenuStrip();
        menu.Items.Add("알리미 열기", null, (_, _) => ShowMainWindow());
        menu.Items.Add("지금 확인", null, async (_, _) => await CheckNowAsync());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("종료", null, (_, _) => ExitApplication());
        _trayIcon.ContextMenuStrip = menu;
    }

    private void ShowMainWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
        BringToFront();
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        if (_reallyExit) return;
        if (e.CloseReason is CloseReason.WindowsShutDown or CloseReason.TaskManagerClosing or CloseReason.ApplicationExitCall)
        {
            SaveFromUi(false);
            _reallyExit = true;
            return;
        }
        e.Cancel = true;
        Hide();
        _trayIcon.ShowBalloonTip(2500, "백그라운드에서 실행 중", "새 글 알림은 계속 작동합니다.", ToolTipIcon.Info);
    }

    private void ExitApplication()
    {
        SaveFromUi(false);
        _reallyExit = true;
        _shutdown.Cancel();
        Close();
    }

    private void SetAllBoards(bool enabled)
    {
        foreach (DataGridViewRow row in _boardsGrid.Rows) row.Cells[0].Value = enabled;
        SetStatus(enabled ? "모든 게시판 알림 켬 · 저장 필요" : "모든 게시판 알림 끔 · 저장 필요");
    }

    private void SetStatus(string text, bool error = false)
    {
        _statusLabel.Text = text;
        _statusDot.ForeColor = error ? Bad : Ok;
    }

    private static void ApplyStartupSetting(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        const string name = "IllusionLiveNotifier";
        if (enabled)
        {
            var path = Environment.ProcessPath ?? Application.ExecutablePath;
            key.SetValue(name, $"\"{path}\" --background");
        }
        else
        {
            key.DeleteValue(name, false);
        }
    }

    private static void OpenUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) return;
        try { Process.Start(new ProcessStartInfo(uri.AbsoluteUri) { UseShellExecute = true }); }
        catch (Exception ex)
        {
            MessageBox.Show($"링크를 열지 못했습니다.\n\n{ex.Message}", "링크 열기 실패",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private static Icon LoadAppIcon()
    {
        try { return Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? (Icon)SystemIcons.Information.Clone(); }
        catch { return (Icon)SystemIcons.Information.Clone(); }
    }

    private static Image? LoadLogo()
    {
        try
        {
            using var stream = typeof(MainForm).Assembly.GetManifestResourceStream("logo.png");
            if (stream is null) return null;
            // Copied into a standalone bitmap: GDI+ keeps reading from the stream otherwise.
            using var raw = Image.FromStream(stream);
            return new Bitmap(raw);
        }
        catch { return null; }
    }

    private static DataGridView NewGrid() => new()
    {
        Dock = DockStyle.Fill,
        AllowUserToAddRows = false,
        AllowUserToDeleteRows = false,
        AllowUserToResizeRows = false,
        BackgroundColor = Canvas,
        BorderStyle = BorderStyle.None,
        CellBorderStyle = DataGridViewCellBorderStyle.SingleHorizontal,
        ColumnHeadersBorderStyle = DataGridViewHeaderBorderStyle.None,
        ColumnHeadersHeight = 38,
        EnableHeadersVisualStyles = false,
        Font = new Font("맑은 고딕", 10f, FontStyle.Regular),
        GridColor = Line,
        RowHeadersVisible = false,
        RowTemplate = { Height = 38 },
        SelectionMode = DataGridViewSelectionMode.FullRowSelect,
        DefaultCellStyle = new DataGridViewCellStyle
        {
            BackColor = Canvas,
            ForeColor = Ink,
            SelectionBackColor = Chip,
            SelectionForeColor = Ink,
            Padding = new Padding(7, 0, 7, 0)
        },
        AlternatingRowsDefaultCellStyle = new DataGridViewCellStyle
        {
            BackColor = Tint,
            ForeColor = Ink,
            SelectionBackColor = Chip,
            SelectionForeColor = Ink,
            Padding = new Padding(7, 0, 7, 0)
        },
        ColumnHeadersDefaultCellStyle = new DataGridViewCellStyle
        {
            BackColor = Canvas,
            ForeColor = Muted,
            Font = new Font("맑은 고딕", 9f, FontStyle.Bold),
            Padding = new Padding(7, 0, 7, 0)
        }
    };

    private static Panel NewPage() => new()
    {
        Dock = DockStyle.Fill,
        BackColor = Canvas,
        Font = new Font("맑은 고딕", 9.5f, FontStyle.Regular)
    };

    // Text-style header actions: the site has no filled buttons, only the gradient underline.
    private void StyleHeaderButton(Button button, bool primary)
    {
        button.AutoSize = true;
        button.AutoSizeMode = AutoSizeMode.GrowAndShrink;
        button.Padding = new Padding(2, 4, 2, 6);
        button.Margin = new Padding(22, 0, 0, 0);
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderSize = 0;
        button.BackColor = Canvas;
        button.ForeColor = primary ? Ink : Color.FromArgb(33, 33, 33);
        button.FlatAppearance.MouseOverBackColor = Canvas;
        button.FlatAppearance.MouseDownBackColor = Tint;
        button.Font = new Font("맑은 고딕", 9.5f, primary ? FontStyle.Bold : FontStyle.Regular);
        button.Cursor = Cursors.Hand;
        button.UseVisualStyleBackColor = false;
        if (!primary) return;

        button.Paint += (_, e) =>
        {
            var bar = new Rectangle(0, button.Height - 3, button.Width, 2);
            if (bar.Width <= 0) return;
            using var brush = BrandBrush(bar);
            e.Graphics.FillRectangle(brush, bar);
        };
    }

    private static LinearGradientBrush BrandBrush(Rectangle area)
    {
        var brush = new LinearGradientBrush(area, GradFrom, GradTo, LinearGradientMode.Horizontal);
        brush.InterpolationColors = new ColorBlend
        {
            Colors = [GradFrom, GradMid, GradTo],
            Positions = [0f, 0.52f, 1f]
        };
        return brush;
    }

    // AutoSize instead of fixed widths: 맑은 고딕 labels overflow hard-coded button widths.
    private static Button SmallButton(string text)
    {
        var button = new Button
        {
            Text = text,
            AutoSize = true,
            AutoSizeMode = AutoSizeMode.GrowAndShrink,
            Padding = new Padding(15, 6, 15, 6),
            Margin = new Padding(8, 0, 0, 0),
            FlatStyle = FlatStyle.Flat,
            BackColor = Canvas,
            ForeColor = Color.FromArgb(33, 33, 33),
            Font = new Font("맑은 고딕", 9.2f, FontStyle.Regular),
            Cursor = Cursors.Hand,
            UseVisualStyleBackColor = false
        };
        button.FlatAppearance.BorderColor = Line;
        button.FlatAppearance.MouseOverBackColor = Tint;
        button.FlatAppearance.MouseDownBackColor = Chip;
        return button;
    }

    private static void AddSettingRow(TableLayoutPanel table, int row, string title, Control value)
    {
        table.Controls.Add(new Label
        {
            Text = title,
            AutoSize = true,
            Font = new Font("맑은 고딕", 10, FontStyle.Bold),
            ForeColor = Ink,
            Margin = new Padding(3, 7, 0, 0)
        }, 0, row);
        table.Controls.Add(value, 1, row);
    }

    private static string Short(string value, int max) =>
        value.Length <= max ? value : value[..Math.Max(0, max - 1)] + "…";
}
