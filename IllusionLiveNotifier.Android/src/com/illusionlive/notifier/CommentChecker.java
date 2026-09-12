package com.illusionlive.notifier;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 댓글 알림. 기존 RSS 사이클 뒤에 붙어 돈다.
 *
 * <p>사이트에 로그인하지 않는다. 댓글은 비로그인으로 읽히고, 신원은 사용자가 설정에 적은
 * 닉네임 문자열 하나로만 판단한다. 그래서 동명이인은 구분하지 못한다.
 */
final class CommentChecker {
    static final String KEY_NICKNAME = "nickname";
    static final String KEY_MY_POSTS = "comment_my_posts";
    static final String KEY_MY_REPLIES = "comment_my_replies";
    static final String KEY_TRACKED = "tracked_posts";
    static final String KEY_SEEN = "seen_comment_ids";
    static final String KEY_SCANNED = "scanned_posts";
    static final String KEY_INITIALIZED = "comments_initialized";

    private static final int MAX_SEEN = 500;
    private static final int MAX_SCANNED = 200;

    private static final String COMMENT_URL = "https://www.illusionlive.com/ajax/post_comment_paging.cm";
    /** 한 사이클에 새로 들여다볼 글 수. 글 페이지가 61 KB 라 발견 비용의 대부분이 여기서 난다. */
    private static final int SCAN_PER_CYCLE = 3;
    /** 한 사이클에 댓글을 다시 확인할 글 수. 추적 수와 무관하게 데이터 사용량을 묶는 상한이다. */
    private static final int CHECK_PER_CYCLE = 8;
    private static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final int MAX_COMMENT_BYTES = 256 * 1024;

    private CommentChecker() {}

    static String nickname(Context context) {
        return FeedChecker.prefs(context).getString(KEY_NICKNAME, "").trim();
    }

    static boolean myPostsEnabled(Context context) {
        return FeedChecker.prefs(context).getBoolean(KEY_MY_POSTS, false);
    }

    static boolean myRepliesEnabled(Context context) {
        return FeedChecker.prefs(context).getBoolean(KEY_MY_REPLIES, false);
    }

    static boolean enabled(Context context) {
        return !nickname(context).isEmpty()
                && (myPostsEnabled(context) || myRepliesEnabled(context));
    }

    /**
     * 닉네임을 저장한다. 값이 바뀌면 모아 둔 추적 목록과 본 댓글 기록을 전부 버린다. 남겨 두면
     * 이전 닉네임 기준으로 고른 글에서 엉뚱한 알림이 나간다. 처음 저장하는 경우에는 두 스위치를
     * 켜 준다.
     */
    static void setNickname(Context context, String value) {
        SharedPreferences preferences = FeedChecker.prefs(context);
        String next = value == null ? "" : value.trim();
        String previous = nickname(context);
        if (next.equals(previous)) return;

        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_NICKNAME, next)
                .remove(KEY_TRACKED)
                .remove(KEY_SEEN)
                .remove(KEY_SCANNED)
                .putBoolean(KEY_INITIALIZED, false);
        if (previous.isEmpty() && !next.isEmpty()) {
            editor.putBoolean(KEY_MY_POSTS, true).putBoolean(KEY_MY_REPLIES, true);
        }
        editor.commit();
    }

    private static Set<String> stringSet(SharedPreferences preferences, String key) {
        return new HashSet<>(preferences.getStringSet(key, Collections.<String>emptySet()));
    }

    /**
     * 이번 사이클에 본 값을 먼저 채우고 남는 자리만 옛 값으로 메운다. {@link FeedChecker} 의
     * seen_ids 와 같은 방식이다. 순서를 뒤집으면 방금 본 댓글이 상한에 밀려 빠지고, 다음
     * 사이클에 같은 댓글을 새 것으로 보아 다시 알린다.
     */
    private static Set<String> capped(Set<String> fresh, Set<String> old, int max) {
        Set<String> next = new HashSet<>(fresh);
        for (String value : old) {
            if (next.size() >= max) break;
            next.add(value);
        }
        return next;
    }

    private static String get(String url, int limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent",
                "IllusionLiveNotifier-Android/1.0 (+https://www.illusionlive.com)");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return readLimited(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** 댓글 목록. 세 값이 모두 있어야 사이트가 응답한다. */
    private static String fetchComments(TrackedPosts.Tracked post) throws IOException {
        String form = "board_code=" + URLEncoder.encode(post.boardCode, "UTF-8")
                + "&post_code=" + URLEncoder.encode(post.postCode, "UTF-8")
                + "&current_page=1";
        HttpURLConnection connection = (HttpURLConnection) new URL(COMMENT_URL).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("User-Agent",
                "IllusionLiveNotifier-Android/1.0 (+https://www.illusionlive.com)");
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(form.getBytes("UTF-8"));
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return unwrapHtml(readLimited(input, MAX_COMMENT_BYTES));
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 응답은 {@code {"msg":"SUCCESS","html":"…"}} 형태다. html 값만 꺼내 역슬래시 이스케이프를
     * 되돌린다. JSON 파서를 들이지 않는 이유는 필요한 필드가 이 하나뿐이기 때문이다.
     * ponytail: 다른 필드가 필요해지면 org.json 으로 바꾼다.
     */
    private static String unwrapHtml(String json) {
        int at = json.indexOf("\"html\":\"");
        if (at < 0) return "";
        int from = at + 8;
        StringBuilder html = new StringBuilder();
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case 'n': html.append('\n'); break;
                    case 'r': html.append('\r'); break;
                    case 't': html.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            html.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: html.append(next);
                }
            } else if (c == '"') {
                break;
            } else {
                html.append(c);
            }
        }
        return html.toString();
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("응답이 " + limit + " 바이트를 넘었습니다");
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), "UTF-8");
    }

    /** 알릴 댓글 한 건과 그 댓글이 달린 글. */
    private static final class Hit {
        final CommentParser.Comment comment;
        final String postUrl;
        final String postTitle;

        Hit(CommentParser.Comment comment, String postUrl, String postTitle) {
            this.comment = comment;
            this.postUrl = postUrl;
            this.postTitle = postTitle;
        }
    }

    /** 캐시에 있으면 글 제목, 없으면 빈 문자열. 알림 문구에만 쓴다. */
    private static String title(Context context, String url) {
        for (FeedParser.Post post : FeedChecker.cachedPosts(context)) {
            if (post.url.equals(url)) return post.title;
        }
        return "";
    }

    /**
     * RSS 처리가 끝난 뒤 같은 스레드에서 이어 돈다. 세 단계다 — 내 글 등록, 발견 스캔,
     * 추적 재확인. 어느 단계가 네트워크 오류로 실패해도 나머지는 계속한다.
     */
    static void run(Context context, List<FeedParser.Post> posts, boolean sendNotifications) {
        if (!enabled(context)) return;

        SharedPreferences preferences = FeedChecker.prefs(context);
        String nickname = nickname(context);
        boolean myPosts = myPostsEnabled(context);
        boolean myReplies = myRepliesEnabled(context);
        boolean initialized = preferences.getBoolean(KEY_INITIALIZED, false);
        long now = System.currentTimeMillis();

        List<TrackedPosts.Tracked> tracked =
                TrackedPosts.decode(preferences.getString(KEY_TRACKED, ""));
        Set<String> scanned = stringSet(preferences, KEY_SCANNED);
        Set<String> seen = stringSet(preferences, KEY_SEEN);
        // 상한을 적용할 때 이번 사이클 것을 먼저 채우려면 무엇이 새로 들어왔는지 알아야 한다.
        Set<String> scannedBefore = new HashSet<>(scanned);
        Set<String> seenNow = new HashSet<>();

        if (myPosts) tracked = registerMyPosts(posts, tracked, scanned, nickname, now);
        if (myReplies) tracked = discover(posts, tracked, scanned, nickname, now);

        List<Hit> fresh = new ArrayList<>();
        for (TrackedPosts.Tracked post : TrackedPosts.due(tracked, CHECK_PER_CYCLE)) {
            List<CommentParser.Comment> comments;
            try {
                comments = CommentParser.parseComments(fetchComments(post));
            } catch (Exception ignored) {
                // checked 를 갱신하지 않으므로 이 글이 다음 사이클 대기열 앞에 그대로 남는다.
                continue;
            }
            tracked = TrackedPosts.markChecked(tracked, post.url, now);

            List<CommentParser.Comment> picked = CommentRules.pick(
                    comments, seen, nickname, post.reason, myPosts, myReplies);
            // 고른 뒤에 기록한다. 순서가 바뀌면 이번에 알릴 것까지 본 것으로 표시된다.
            for (CommentParser.Comment comment : comments) seenNow.add(comment.code);

            // 첫 시딩에서는 기준점만 잡고 알리지 않는다.
            if (!initialized) continue;
            String postTitle = title(context, post.url);
            for (CommentParser.Comment comment : picked) {
                fresh.add(new Hit(comment, post.url, postTitle));
            }
        }

        Set<String> scannedNow = new HashSet<>(scanned);
        scannedNow.removeAll(scannedBefore);

        preferences.edit()
                .putString(KEY_TRACKED, TrackedPosts.encode(tracked))
                .putStringSet(KEY_SCANNED, capped(scannedNow, scannedBefore, MAX_SCANNED))
                .putStringSet(KEY_SEEN, capped(seenNow, seen, MAX_SEEN))
                .putBoolean(KEY_INITIALIZED, true)
                .commit();

        if (sendNotifications && !fresh.isEmpty()) notifyComments(context, fresh);
    }

    /** RSS 에서 내가 쓴 글을 골라 추적에 넣는다. 두 코드를 얻으려 글 페이지를 한 번 받는다. */
    private static List<TrackedPosts.Tracked> registerMyPosts(
            List<FeedParser.Post> posts, List<TrackedPosts.Tracked> tracked,
            Set<String> scanned, String nickname, long now) {
        for (FeedParser.Post post : posts) {
            if (!nickname.equals(post.author)) continue;
            if (contains(tracked, post.url)) continue;
            tracked = withCodes(tracked, post.url, TrackedPosts.MY_POST, now);
            scanned.add(post.id); // 내 글은 발견 스캔이 다시 열어 볼 필요가 없다
        }
        return tracked;
    }

    /**
     * 아직 안 본 글을 사이클당 {@link #SCAN_PER_CYCLE} 개까지 열어, 내 댓글이 있으면 추적에
     * 넣는다. 있든 없든 {@link #KEY_SCANNED} 에 적어 두 번 열지 않는다.
     */
    private static List<TrackedPosts.Tracked> discover(
            List<FeedParser.Post> posts, List<TrackedPosts.Tracked> tracked,
            Set<String> scanned, String nickname, long now) {
        int budget = SCAN_PER_CYCLE;
        for (FeedParser.Post post : posts) {
            if (budget <= 0) break;
            if (scanned.contains(post.id) || contains(tracked, post.url)) continue;
            budget--;
            scanned.add(post.id);

            String page;
            try {
                page = get(post.url, MAX_PAGE_BYTES);
            } catch (Exception ignored) {
                scanned.remove(post.id); // 실패한 글은 다음 사이클에 다시 시도한다
                continue;
            }
            String postCode = CommentParser.postCode(page);
            String boardCode = CommentParser.boardCode(page);
            if (postCode.isEmpty() || boardCode.isEmpty()) continue;

            TrackedPosts.Tracked probe =
                    new TrackedPosts.Tracked(post.url, postCode, boardCode, now, 0L, 0);
            try {
                for (CommentParser.Comment comment : CommentParser.parseComments(fetchComments(probe))) {
                    if (nickname.equals(comment.author)) {
                        tracked = TrackedPosts.add(tracked, post.url, postCode, boardCode,
                                TrackedPosts.MY_COMMENT, now);
                        break;
                    }
                }
            } catch (Exception ignored) {
                scanned.remove(post.id);
            }
        }
        return tracked;
    }

    /** 글 페이지에서 두 코드를 받아 추적에 넣는다. 실패하면 목록을 그대로 돌려준다. */
    private static List<TrackedPosts.Tracked> withCodes(
            List<TrackedPosts.Tracked> tracked, String url, int reason, long now) {
        try {
            String page = get(url, MAX_PAGE_BYTES);
            return TrackedPosts.add(tracked, url,
                    CommentParser.postCode(page), CommentParser.boardCode(page), reason, now);
        } catch (Exception ignored) {
            return tracked;
        }
    }

    private static boolean contains(List<TrackedPosts.Tracked> tracked, String url) {
        for (TrackedPosts.Tracked item : tracked) {
            if (item.url.equals(url)) return true;
        }
        return false;
    }

    /**
     * 자리표시자 — 실제 알림 발송(채널 생성, 문구 구성)은 다음 작업에서 채운다. {@link #run} 이
     * 이 심볼을 이미 참조하므로, 본문 없이 두면 이 파일 자체가 컴파일되지 않는다.
     * ponytail: 다음 작업이 이 본문을 Notification.Builder 구현으로 교체한다.
     */
    private static void notifyComments(Context context, List<Hit> hits) {
    }
}
