package com.illusionlive.notifier;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public final class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 7;

    // illusionlive.com palette: #353958 is the site's declared brand colour, #757DBD its link
    // tone, #212121 its body text. Modern treatment (gradient, large radii, pills) on top.
    // Not final: applyTheme() swaps the whole set for the dark one before any view is built.
    // Package-private: TutorialArt paints its mock-ups with the same live palette.
    static int BRAND = 0xFF353958;
    private static int BRAND_DEEP = 0xFF222438;
    private static int BRAND_LIGHT = 0xFF5A6096;
    static int ACCENT = 0xFF757DBD;
    static int CANVAS = 0xFFF4F5F9;
    static int SURFACE = 0xFFFFFFFF;
    static int INK = 0xFF212121;
    static int MUTED = 0xFF6B6F86;
    static int LINE = 0xFFE4E6EF;
    private static int CHIP = 0xFFE9EAF3;
    static int ON_BRAND = 0xFFFFFFFF;
    private static final int ON_BRAND_SOFT = 0x33FFFFFF;
    private static final int PULL_DP = 72;

    // Pull-to-refresh spinner: light blue, 1.5x the old 28dp, drops from the top to the middle.
    private static final int SPINNER = 0xFF7EC4F0;
    private static final int SPINNER_DP = 42;
    private static final int SPINNER_DROP_MS = 420;
    private static final String STATE_SETTINGS_SHOWN = "settings_shown";

    private static final String[] BOARD_DATA = {
            "newspage|최신 소식|공식|1", "IL_Community|통합 커뮤니티|공식|1",
            "82|냐루네 공지|냐루|1", "community_nyaru|냐묄 놀이터|냐루|1", "99|선물 수여식|냐루|1",
            "chung01|예소리 이야기|청예솔|1", "chung02|이야기 주머니|청예솔|1", "chung03|썰풀이 마당|청예솔|1",
            "seomong|서몽 게시판|서몽|1", "65|TEST|서몽|0",
            "community_rusticana|루스티카나 커뮤니티|루스티카나|1", "community_fuyuno|후유노의 겨울카페|후유노|1",
            "LEEE1|리이가 말한다!|리이|1", "LEEE2|리이리와|리이|1", "LEEE3|리이야 이거해줘!|리이|1",
            "community_rom|부적단 이야기|디롬|1", "75|늑대네 공지|디롬|1", "80|To.디롬|디롬|1",
            "77|팬작업물|디롬|1", "78|비상업용 커미션|디롬|1", "79|상업용 커미션|디롬|1",
            "sohee1|소히가 할말있어|소히|1", "sohee2|소랑이가 할말있어|소히|1",
            "88|그루에게|쿠모리 키피|1", "community_keepie|키피에게|쿠모리 키피|1",
            "eb|이비의 방|이비 EB|1", "eb_notice|이비네 공지|이비 EB|1", "eb_diary|이비의 일기|이비 EB|1",
            "bb|비비단의 방|비비|1", "bb_free|비비 놀이터|비비|1", "bb_game|추천과 팁|비비|1",
            "comet_001|공지|코메|1", "comet_002|선장일지|코메|1", "119|보상전달|코메|1",
            "comet_003|뜽어일지|코메|1", "comet_004|선장 이거봐!|코메|1",
            "124|사서님 서한|위즐리어카|1", "125|꼬슴이 우편|위즐리어카|1",
            "128|공지|도라리|1", "129|잡담|도라리|1", "130|선원 잡담|도라리|1", "131|이거 해줘|도라리|1",
            "133|공지|유메루|1", "134|방종후기 / 잡담|유메루|1", "135|꿈몽쓰의 공간|유메루|1",
            "136|게임추천 / 팁 / 정보|유메루|1", "137|메루야 이거 봐라|유메루|1"
    };

    private final PostAdapter adapter = new PostAdapter();
    private FrameLayout content;
    private View recentView;
    private ImageButton settingsButton;
    private ProgressBar progress;
    private TextView batteryRow;
    private boolean settingsShown;
    private boolean refreshing;
    private OnBackInvokedCallback backCallback;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FeedChecker.ensureNotificationChannel(this);
        applyTheme(darkEnabled());
        buildUi();
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_SETTINGS_SHOWN))
            showSettings();
        else showRecent();
        adapter.setPosts(FeedChecker.cachedPosts(this));
        FeedAlarmReceiver.sync(this);
        if (!requestNotificationPermission()) maybeAskBatteryExemption();
        checkNow();
        maybeShowTutorial();
    }

    /** The exemption can be granted or revoked in system settings while the app is away. */
    @Override protected void onResume() {
        super.onResume();
        bindBatteryRow();
    }

    /** Survives the recreate() a dark-mode toggle triggers, so the toggle stays on screen. */
    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SETTINGS_SHOWN, settingsShown);
    }

    // ---------------------------------------------------------------- theme

    /** No stored choice means follow the system's night setting. */
    private boolean darkEnabled() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return FeedChecker.prefs(this).getBoolean(FeedChecker.KEY_DARK, night);
    }

    /** Same hues as the light theme, pulled down onto a night canvas. */
    private void applyTheme(boolean dark) {
        BRAND = dark ? 0xFFA9B0E8 : 0xFF353958;
        BRAND_DEEP = dark ? 0xFF15161F : 0xFF222438;
        BRAND_LIGHT = dark ? 0xFF3A3F62 : 0xFF5A6096;
        ACCENT = dark ? 0xFF8F97D8 : 0xFF757DBD;
        CANVAS = dark ? 0xFF101119 : 0xFFF4F5F9;
        SURFACE = dark ? 0xFF1B1D28 : 0xFFFFFFFF;
        INK = dark ? 0xFFECEDF3 : 0xFF212121;
        MUTED = dark ? 0xFF9195AB : 0xFF6B6F86;
        LINE = dark ? 0xFF2B2E3C : 0xFFE4E6EF;
        CHIP = dark ? 0xFF262939 : 0xFFE9EAF3;
        ON_BRAND = dark ? 0xFF14151E : 0xFFFFFFFF;

        // The theme XML only knows the light palette: repaint what it set.
        getWindow().setBackgroundDrawable(new ColorDrawable(CANVAS));
    }

    /**
     * The navigation bar's icon tint, set here rather than in {@link #applyTheme}: the decor view
     * that owns the insets controller is only built once the window is attached, and asking for it
     * from onCreate throws.
     */
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = getWindow().getInsetsController();
            if (bars != null) bars.setSystemBarsAppearance(
                    darkEnabled() ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    // ---------------------------------------------------------------- shell

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CANVAS);

        final LinearLayout header = buildHeader();
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // targetSdk 35+ lays out edge to edge: keep the gradient behind the status bar but push
        // its text clear of the clock, and lift the list above the navigation bar.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    header.setPadding(dp(18), bars.top + dp(12), dp(18), dp(14));
                    content.setPadding(0, 0, 0, bars.bottom);
                }
                return insets;
            }
        });

        setContentView(root);
        recentView = buildRecentView();
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(18), dp(18), dp(14));
        GradientDrawable sky = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{BRAND_DEEP, BRAND_LIGHT});
        header.setBackground(sky);

        TextView title = text("일루전 라이브 알리미", 21);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.drawable.ic_settings_gear);
        settingsButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        settingsButton.setPadding(dp(9), dp(9), dp(9), dp(9));
        settingsButton.setStateListAnimator(null);
        settingsButton.setContentDescription("알림 설정");
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (settingsShown) showRecent(); else showSettings();
            }
        });
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        return header;
    }

    private void styleSettingsButton(boolean active) {
        settingsButton.setBackground(ripple(active ? SURFACE : ON_BRAND_SOFT, 21,
                active ? BRAND : Color.WHITE));
        settingsButton.setColorFilter(active ? BRAND : Color.WHITE);
    }

    // ------------------------------------------------------------ recent tab

    private View buildRecentView() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(12), dp(12), dp(12), dp(12));

        FrameLayout listHolder = new FrameLayout(this);
        listHolder.setBackground(rounded(SURFACE, 14, LINE));
        listHolder.setClipToOutline(true);
        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(1);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openPost(adapter.getItem(position)));
        list.setOnTouchListener(new PullToRefresh());

        TextView empty = text("아직 불러온 글이 없습니다.\n화면을 아래로 당겨 새로고침하세요.", 15);
        empty.setTextColor(MUTED);
        empty.setGravity(Gravity.CENTER);
        list.setEmptyView(empty);
        listHolder.addView(list, new FrameLayout.LayoutParams(-1, -1));
        listHolder.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(SPINNER));
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                dp(SPINNER_DP), dp(SPINNER_DP), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        listHolder.addView(progress, progressParams);

        pane.addView(listHolder, new LinearLayout.LayoutParams(-1, -1));
        return pane;
    }

    /**
     * Pull down at the top of the list to refresh. Plain framework only — SwipeRefreshLayout lives
     * in androidx and this app has no dependencies.
     */
    private final class PullToRefresh implements View.OnTouchListener {
        private float startY = -1f;
        private boolean pulled;

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = view.canScrollVertically(-1) ? -1f : event.getY();
                    pulled = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (startY < 0f && !view.canScrollVertically(-1)) startY = event.getY();
                    pulled = startY >= 0f && event.getY() - startY > dp(PULL_DP);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (pulled && event.getActionMasked() == MotionEvent.ACTION_UP) checkNow();
                    startY = -1f;
                    pulled = false;
                    break;
                default:
                    break;
            }
            return false; // let the ListView scroll as usual
        }
    }

    private void showRecent() {
        settingsShown = false;
        styleSettingsButton(false);
        content.removeAllViews();
        content.addView(recentView, new FrameLayout.LayoutParams(-1, -1));
        syncBackCallback();
    }

    /**
     * targetSdk 36 makes predictive back mandatory — the manifest opt-out no longer works and
     * {@link #onBackPressed} is never called on API 33+, so the settings pane registers its own
     * back callback there. onBackPressed still covers API 26–32.
     */
    private void syncBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        if (!settingsShown) return;
        backCallback = this::showRecent;
        dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    // ---------------------------------------------------------- settings tab

    private void showSettings() {
        settingsShown = true;
        styleSettingsButton(true);
        final SharedPreferences preferences = FeedChecker.prefs(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(12), dp(12), dp(12), dp(28));

        LinearLayout themeCard = card();
        themeCard.addView(checkBox("다크 모드", 16, darkEnabled(),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton view, boolean checked) {
                        preferences.edit().putBoolean(FeedChecker.KEY_DARK, checked).commit();
                        recreate(); // the palette is read while views are built, so rebuild them
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
        pane.addView(themeCard, matchWrap(dp(18)));

        LinearLayout backgroundCard = card();
        backgroundCard.addView(checkBox("백그라운드 새 글 알림", 16,
                preferences.getBoolean(FeedChecker.KEY_BACKGROUND, true),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton view, boolean checked) {
                        preferences.edit().putBoolean(FeedChecker.KEY_BACKGROUND, checked).commit();
                        FeedAlarmReceiver.sync(MainActivity.this);
                        if (checked) requestNotificationPermission();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        TextView guide = text("앱이 닫혀 있어도 새 글을 자동으로 확인합니다.", 13);
        guide.setTextColor(MUTED);
        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(-1, -2);
        guideParams.setMargins(0, dp(6), 0, 0);
        backgroundCard.addView(guide, guideParams);

        batteryRow = text("", 13);
        batteryRow.setPadding(dp(12), dp(10), dp(12), dp(11));
        batteryRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { requestBatteryExemption(); }
        });
        LinearLayout.LayoutParams batteryParams = new LinearLayout.LayoutParams(-1, -2);
        batteryParams.setMargins(0, dp(12), 0, 0);
        backgroundCard.addView(batteryRow, batteryParams);
        bindBatteryRow();
        pane.addView(backgroundCard, matchWrap(dp(18)));

        pane.addView(sectionTitle("게시판별 알림"), matchWrap(dp(4)));
        TextView hint = text("체크한 게시판의 새 글만 알립니다. 변경은 바로 저장됩니다.", 12.5f);
        hint.setTextColor(MUTED);
        pane.addView(hint, matchWrap(dp(12)));

        Set<String> known = new HashSet<>();
        String group = null;
        LinearLayout groupCard = null;
        for (String data : BOARD_DATA) {
            Board board = Board.parse(data);
            known.add(board.slug);
            if (!board.group.equals(group)) {
                group = board.group;
                pane.addView(groupLabel(group), wrapWrap(dp(6)));
                groupCard = card();
                pane.addView(groupCard, matchWrap(dp(16)));
            }
            addBoardRow(groupCard, preferences, board);
        }

        Set<String> dynamic = new TreeSet<>(preferences.getStringSet(FeedChecker.KEY_DYNAMIC_BOARDS,
                new HashSet<String>()));
        LinearLayout otherCard = null;
        for (String slug : dynamic) {
            if (known.contains(slug)) continue;
            if (otherCard == null) {
                pane.addView(groupLabel("기타"), wrapWrap(dp(6)));
                otherCard = card();
                pane.addView(otherCard, matchWrap(dp(16)));
            }
            addBoardRow(otherCard, preferences, new Board(slug, slug, "기타", true));
        }

        scroll.addView(pane, new ScrollView.LayoutParams(-1, -2));
        content.removeAllViews();
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        syncBackCallback();
    }

    private void addBoardRow(LinearLayout parent, final SharedPreferences preferences, final Board board) {
        if (parent.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, 1);
            dividerParams.setMargins(0, dp(12), 0, dp(12));
            parent.addView(divider, dividerParams);
        }

        parent.addView(checkBox(board.name, 15.5f,
                preferences.getBoolean(FeedChecker.boardEnabledKey(board.slug), board.enabledByDefault),
                new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton view, boolean checked) {
                        preferences.edit()
                                .putBoolean(FeedChecker.boardEnabledKey(board.slug), checked).commit();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
    }

    // -------------------------------------------------------------- first run

    /** Title and body of each tutorial page; the picture for it lives in {@link TutorialArt}. */
    private static final String[][] TUTORIAL = {
            {"알림 받을 게시판 고르기",
                    "오른쪽 위 톱니바퀴를 누르면 게시판 목록이 열립니다.\n체크한 게시판의 새 글만 알려 드립니다."},
            {"당겨서 새로고침",
                    "목록 맨 위에서 아래로 당기면\n새 글을 바로 확인합니다."},
            {"글 열어보기",
                    "글을 누르면 브라우저에서\n원문이 열립니다."},
            {"앱을 닫아도 알림",
                    "백그라운드에서 새 글을 확인해 알림을 보냅니다.\nAndroid 절전 상태에서는 조금 늦어질 수 있습니다."},
            {"지금 있는 글은 알리지 않아요",
                    "첫 실행 시점의 글은 기준으로만 저장하고,\n이후 올라오는 새 글부터 알려 드립니다."}
    };

    /**
     * A paged card over everything on the very first launch: one drawn example per step. Added to
     * the window rather than to {@link #content}, so the tabs cannot swap it away before it is
     * dismissed.
     */
    private void maybeShowTutorial() {
        final SharedPreferences preferences = FeedChecker.prefs(this);
        if (preferences.getBoolean(FeedChecker.KEY_TUTORIAL_SEEN, false)) return;

        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setClickable(true); // swallow taps meant for the list underneath

        LinearLayout card = card();

        final TutorialArt art = new TutorialArt(this);
        LinearLayout.LayoutParams artParams = new LinearLayout.LayoutParams(-1, dp(178));
        artParams.setMargins(0, dp(4), 0, dp(16));
        card.addView(art, artParams);

        final TextView title = sectionTitle("");
        card.addView(title, matchWrap(dp(8)));

        final TextView body = text("", 14.5f);
        body.setTextColor(MUTED);
        body.setLineSpacing(dp(4), 1f);
        body.setMinLines(2); // keeps the card from resizing between pages
        card.addView(body, matchWrap(dp(16)));

        final LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < TUTORIAL.length; i++) {
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
            dotParams.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(new View(this), dotParams);
        }
        card.addView(dots, matchWrap(dp(16)));

        final TextView skip = text("건너뛰기", 15f);
        skip.setTextColor(MUTED);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(dp(16), dp(13), dp(16), dp(13));
        skip.setBackground(ripple(SURFACE, 12, MUTED));

        final TextView next = text("", 15.5f);
        next.setTypeface(Typeface.DEFAULT_BOLD);
        next.setTextColor(ON_BRAND);
        next.setGravity(Gravity.CENTER);
        next.setPadding(0, dp(13), 0, dp(13));
        next.setBackground(ripple(BRAND, 12, ON_BRAND));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(skip, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, -2, 1);
        nextParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(next, nextParams);
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        final int[] step = {0};
        final Runnable render = new Runnable() {
            @Override public void run() {
                art.setStep(step[0]);
                title.setText(TUTORIAL[step[0]][0]);
                body.setText(TUTORIAL[step[0]][1]);
                for (int i = 0; i < dots.getChildCount(); i++)
                    dots.getChildAt(i).setBackground(rounded(i == step[0] ? BRAND : LINE, 4, 0));
                boolean last = step[0] == TUTORIAL.length - 1;
                next.setText(last ? "시작하기" : "다음");
                skip.setVisibility(last ? View.GONE : View.VISIBLE);
            }
        };

        final Runnable dismiss = new Runnable() {
            @Override public void run() {
                preferences.edit().putBoolean(FeedChecker.KEY_TUTORIAL_SEEN, true).commit();
                ((ViewGroup) scrim.getParent()).removeView(scrim);
            }
        };
        skip.setOnClickListener(view -> dismiss.run());
        next.setOnClickListener(view -> {
            if (step[0] == TUTORIAL.length - 1) {
                dismiss.run();
                return;
            }
            step[0]++;
            render.run();
        });
        art.setOnClickListener(view -> next.performClick()); // tapping the picture moves on too
        render.run();

        // The card scrolls on short screens rather than pushing its buttons off the bottom.
        ScrollView cardScroll = new ScrollView(this);
        cardScroll.addView(card, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        cardParams.setMargins(dp(22), dp(22), dp(22), dp(22));
        scrim.addView(cardScroll, cardParams);
        getWindow().addContentView(scrim, new FrameLayout.LayoutParams(-1, -1));
    }

    // --------------------------------------------------------------- actions

    private void checkNow() {
        if (refreshing) return;
        refreshing = true;
        progress.setVisibility(View.VISIBLE);
        dropSpinner();
        FeedChecker.check(this, true, new FeedChecker.Listener() {
            @Override public void onComplete(final FeedChecker.Result result) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        refreshing = false;
                        progress.animate().cancel();
                        progress.setVisibility(View.GONE);
                        if (result.busy) return;
                        if (result.error != null) {
                            // The cached list stays on screen; only say the refresh failed.
                            toast("확인 실패 · " + result.error);
                        } else {
                            adapter.setPosts(result.posts);
                        }
                    }
                });
            }
        });
    }

    /** Slides the spinner in from just above the list and settles it in the middle. */
    private void dropSpinner() {
        View holder = (View) progress.getParent();
        progress.animate().cancel();
        progress.setTranslationY(-dp(SPINNER_DP));
        progress.animate()
                .translationY(Math.max(0f, (holder.getHeight() - dp(SPINNER_DP)) / 2f))
                .setDuration(SPINNER_DROP_MS)
                .start();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (settingsShown) {
            showRecent();
            return;
        }
        super.onBackPressed();
    }

    private void openPost(FeedParser.Post post) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(post.url)));
        } catch (ActivityNotFoundException error) {
            toast("링크를 열 앱이 없습니다.");
        }
    }

    /** @return true when a dialog was raised, so the caller can wait its turn behind it. */
    private boolean requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return false;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        return true;
    }

    // ----------------------------------------------------- battery exemption

    /**
     * Doze holds the background alarm to roughly one fire per 9 minutes, which is what makes
     * new post notifications arrive late or not at all. This exemption lifts that clamp. It is
     * not a fix for the OEM app killers (Samsung sleeping apps, MIUI autostart) - those stay a
     * manual setting.
     */
    private boolean batteryExempt() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** One unprompted ask, and only once the tutorial is done, so a first launch stays calm. */
    private void maybeAskBatteryExemption() {
        SharedPreferences preferences = FeedChecker.prefs(this);
        if (preferences.getBoolean(FeedChecker.KEY_BATTERY_ASKED, false)) return;
        if (!preferences.getBoolean(FeedChecker.KEY_TUTORIAL_SEEN, false)) return;
        if (!FeedChecker.backgroundEnabled(this) || batteryExempt()) return;
        preferences.edit().putBoolean(FeedChecker.KEY_BATTERY_ASKED, true).commit();
        requestBatteryExemption();
    }

    private void requestBatteryExemption() {
        if (batteryExempt()) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException error) {
            // Some ROMs drop the per-app dialog; the full optimization list is always there.
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                toast("절전 예외 설정을 열 수 없습니다.");
            }
        }
    }

    /** States the live setting, and is a button only while the exemption is still missing. */
    private void bindBatteryRow() {
        if (batteryRow == null) return;
        boolean exempt = batteryExempt();
        batteryRow.setText(exempt
                ? "절전 예외 허용됨 · 새 글을 제때 알립니다."
                : "절전 예외 허용하기 · 지금은 알림이 최대 9분 늦습니다.");
        batteryRow.setTextColor(exempt ? MUTED : BRAND);
        batteryRow.setTypeface(exempt ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        batteryRow.setBackground(exempt ? rounded(CHIP, 10, 0) : ripple(CHIP, 10, BRAND));
        batteryRow.setClickable(!exempt);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return;
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)
            toast("알림 권한을 허용해야 새 글 알림이 표시됩니다.");
        maybeAskBatteryExemption(); // queued behind the dialog the user just answered
    }

    // --------------------------------------------------------------- drawing

    private GradientDrawable rounded(int fill, int radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    private Drawable ripple(int fill, int radiusDp, int rippleColor) {
        int translucent = (rippleColor & 0x00FFFFFF) | 0x40000000;
        return new RippleDrawable(ColorStateList.valueOf(translucent),
                rounded(fill, radiusDp, 0), rounded(Color.WHITE, radiusDp, 0));
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(14), dp(16), dp(16));
        view.setBackground(rounded(SURFACE, 14, LINE));
        return view;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 18);
        view.setTextColor(INK);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    /** Member name in that member's own colour; two-colour members use the first. */
    private TextView groupLabel(String value) {
        int[] colors = MemberColors.of(value);
        TextView view = text(value, 12.5f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(12), dp(5), dp(12), dp(6));
        view.setBackground(rounded(CHIP, 9, 0));
        if (colors == null) {
            view.setTextColor(BRAND);
            return view;
        }
        view.setTextColor(MemberColors.readableOn(CHIP, colors[0]));
        return view;
    }

    // --------------------------------------------------------------- helpers

    private CheckBox checkBox(String label, float sp, boolean checked,
                              CompoundButton.OnCheckedChangeListener listener) {
        CheckBox view = new CheckBox(this);
        view.setText(label);
        view.setTextSize(sp);
        view.setTextColor(INK);
        view.setButtonTintList(ColorStateList.valueOf(ACCENT));
        view.setChecked(checked);
        view.setOnCheckedChangeListener(listener);
        return view;
    }

    private TextView text(String value, float sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(INK);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, bottomMargin);
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, 0, bottomMargin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private Board findBoard(String slug) {
        for (String data : BOARD_DATA) {
            Board board = Board.parse(data);
            if (board.slug.equalsIgnoreCase(slug)) return board;
        }
        return null;
    }

    /** "멤버 · 게시판 · 작성자 · 시간". The member name is its own view so it can take its own colour. */
    private void bindMeta(LinearLayout metaRow, FeedParser.Post post) {
        TextView member = (TextView) metaRow.getChildAt(0);
        TextView rest = (TextView) metaRow.getChildAt(1);
        Board board = findBoard(post.boardSlug);
        String author = post.author.isEmpty() ? "" : " · " + post.author;
        String date = post.published == 0 ? "" : " · " +
                new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date(post.published));

        if (board == null) {
            member.setText("");
            rest.setText(post.boardSlug + author + date);
            return;
        }

        member.setText(board.group);
        rest.setText(" · " + board.name + author + date);
        int[] colors = MemberColors.of(board.group);
        if (colors == null) {
            member.setTextColor(MUTED);
            return;
        }
        member.setTextColor(MemberColors.readableOn(SURFACE, colors[0]));
    }

    private final class PostAdapter extends BaseAdapter {
        private final List<FeedParser.Post> posts = new ArrayList<>();

        void setPosts(List<FeedParser.Post> next) {
            posts.clear();
            posts.addAll(next);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return posts.size(); }
        @Override public FeedParser.Post getItem(int position) { return posts.get(position); }
        @Override public long getItemId(int position) { return posts.get(position).id.hashCode(); }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) row = buildPostRow();
            FeedParser.Post post = getItem(position);
            ((TextView) row.getChildAt(0)).setText(post.title);
            bindMeta((LinearLayout) row.getChildAt(1), post);
            return row;
        }

        private LinearLayout buildPostRow() {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(13), dp(16), dp(13));

            TextView title = text("", 15.5f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(2);
            row.addView(title, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout meta = new LinearLayout(MainActivity.this);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            TextView member = text("", 12.5f);
            member.setTypeface(Typeface.DEFAULT_BOLD);
            member.setSingleLine(true);
            meta.addView(member, new LinearLayout.LayoutParams(-2, -2));
            TextView rest = text("", 12.5f);
            rest.setTextColor(MUTED);
            rest.setSingleLine(true);
            rest.setEllipsize(TextUtils.TruncateAt.END);
            meta.addView(rest, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
            metaParams.setMargins(0, dp(4), 0, 0);
            row.addView(meta, metaParams);
            return row;
        }
    }

    private static final class Board {
        final String slug;
        final String name;
        final String group;
        final boolean enabledByDefault;

        Board(String slug, String name, String group, boolean enabledByDefault) {
            this.slug = slug;
            this.name = name;
            this.group = group;
            this.enabledByDefault = enabledByDefault;
        }

        static Board parse(String data) {
            String[] parts = data.split("\\|", -1);
            return new Board(parts[0], parts[1], parts[2], "1".equals(parts[3]));
        }
    }
}
