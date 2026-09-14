package com.illusionlive.notifier;

import android.app.AlertDialog;
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
        nickname.setTextColor(MainActivity.INK);
        nickname.setHintTextColor(MainActivity.MUTED);
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
                next.setText(step[0] == PAGES.length - 1 ? "시작하기" : "다음");
            }
        };

        final Runnable dismiss = new Runnable() {
            @Override public void run() {
                // 닉네임 저장은 여기서 하지 않는다 — 건너뛰기는 무엇을 적었든 저장 없이 닫기만 한다.
                preferences.edit().putBoolean(FeedChecker.KEY_TUTORIAL_SEEN, true).commit();
                // 확인 창이 두 개 떴다가 둘 다 저장하면 두 번 불린다 - 이미 닫혔으면 그냥 둔다.
                ViewGroup parent = (ViewGroup) scrim.getParent();
                if (parent != null) parent.removeView(scrim);
            }
        };
        skip.setOnClickListener(view -> dismiss.run());
        next.setOnClickListener(view -> {
            if (step[0] < PAGES.length - 1) {
                step[0]++;
                render.run();
                return;
            }
            // 마지막 쪽: 입력한 닉네임이 최근 글 작성자 중에 없으면 오타인지 한 번 확인한다.
            String value = nickname.getText().toString().trim();
            if (value.isEmpty() || CommentChecker.knownAuthor(activity, value)) {
                CommentChecker.setNickname(activity, value);
                dismiss.run();
                return;
            }
            new AlertDialog.Builder(activity)
                    .setMessage("최근 글에서 '" + value + "' 을(를) 찾지 못했습니다.\n그대로 저장할까요?")
                    .setNegativeButton("다시 입력", null)
                    .setPositiveButton("저장", (dialog, which) -> {
                        CommentChecker.setNickname(activity, value);
                        dismiss.run();
                    })
                    .show();
        });
        // 그림을 눌러도 넘어간다. 닉네임 쪽에서는 키보드를 닫으려고 누른 것이 저장이 되지 않게 막는다.
        art.setOnClickListener(view -> {
            if (step[0] < PAGES.length - 1) next.performClick();
        });
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
