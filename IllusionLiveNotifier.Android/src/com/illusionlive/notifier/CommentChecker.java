package com.illusionlive.notifier;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

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
    static final String KEY_CANDIDATES = "comment_candidates";

    private static final int MAX_SEEN = 500;

    private static final String COMMENT_URL = "https://www.illusionlive.com/ajax/post_comment_paging.cm";
    /** 한 사이클에 새로 들여다볼 글 수. 글 페이지가 61 KB 라 발견 비용의 대부분이 여기서 난다. */
    private static final int SCAN_PER_CYCLE = 3;
    /** 한 사이클에 댓글을 다시 확인할 글 수. 추적 수와 무관하게 데이터 사용량을 묶는 상한이다. */
    private static final int CHECK_PER_CYCLE = 8;
    /**
     * 한 사이클에 내 댓글이 새로 달렸는지 다시 볼 후보 글 수. 댓글 목록만 받으므로 한 건에 약
     * 7 KB 다. 후보는 {@link TrackedPosts#MAX} 개까지라 한 바퀴에 최대 10 사이클이 걸린다.
     * ponytail: 후보가 TrackedPosts.MAX 를 같이 써서 게시가 최근인 20글(하루 12글 기준 약 1.7일)
     * 까지만 다시 본다. 더 오래된 글까지 봐야 하면 prune 에 상한 인자를 더한다.
     */
    private static final int RESCAN_PER_CYCLE = 2;
    private static final int MAX_PAGE_BYTES = 1024 * 1024;
    private static final int MAX_COMMENT_BYTES = 256 * 1024;
    private static final String CHANNEL_ID = "new_comments";
    /** 여러 건을 묶을 때 쓰는 고정 id. 글 알림의 8702 와 겹치지 않게 둔다. */
    private static final int SUMMARY_ID = 8703;

    private CommentChecker() {}

    static String nickname(Context context) {
        return FeedChecker.prefs(context).getString(KEY_NICKNAME, "").trim();
    }

    /** 캐시된 글 작성자 중에 이 닉네임이 있는지. 네트워크를 쓰지 않는다. 오타 확인용이라 저장을 막지 않는다. */
    static boolean knownAuthor(Context context, String nickname) {
        for (FeedParser.Post post : FeedChecker.cachedPosts(context)) {
            if (nickname.equals(post.author)) return true;
        }
        return false;
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

    static void ensureNotificationChannel(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "댓글 알림", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("내 글에 달린 댓글과 내 댓글에 달린 답");
        manager.createNotificationChannel(channel);
    }

    /**
     * 닉네임을 저장한다. 값이 바뀌면 모아 둔 추적 목록과 본 댓글 기록을 전부 버린다. 남겨 두면
     * 이전 닉네임 기준으로 고른 글에서 엉뚱한 알림이 나간다. 처음 저장하는 경우에는 두 스위치를
     * 켜 준다.
     */
    static synchronized void setNickname(Context context, String value) {
        SharedPreferences preferences = FeedChecker.prefs(context);
        String next = value == null ? "" : value.trim();
        String previous = nickname(context);
        if (next.equals(previous)) return;

        SharedPreferences.Editor editor = preferences.edit()
                .putString(KEY_NICKNAME, next)
                .remove(KEY_TRACKED)
                .remove(KEY_SEEN)
                .remove(KEY_SCANNED)
                .remove(KEY_CANDIDATES);
        if (previous.isEmpty() && !next.isEmpty()) {
            editor.putBoolean(KEY_MY_POSTS, true).putBoolean(KEY_MY_REPLIES, true);
        }
        editor.commit();
    }

    private static Set<String> stringSet(SharedPreferences preferences, String key) {
        return new HashSet<>(preferences.getStringSet(key, Collections.<String>emptySet()));
    }

    private static String get(String url, int limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        // 댓글 단계는 마감 시각 안에서 돈다. 요청 하나가 멈춰 서서 마감을 크게 넘기지 않게
        // RSS 요청보다 짧게 잡는다.
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(8_000);
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
        // 댓글 단계는 마감 시각 안에서 돈다. 요청 하나가 멈춰 서서 마감을 크게 넘기지 않게
        // RSS 요청보다 짧게 잡는다.
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(8_000);
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
     * RSS 처리가 끝난 뒤 같은 스레드에서 이어 돈다. 네 단계다 — 내 글 등록, 발견 스캔,
     * 추적 재확인, 후보 재스캔. 어느 단계가 네트워크 오류로 실패해도 나머지는 계속한다.
     *
     * <p>{@code deadline} 은 {@link SystemClock#elapsedRealtime()} 기준 마감 시각이다. 각
     * 단계는 새 네트워크 요청을 시작하기 전에 이를 확인해, 지났으면 그 단계의 루프만 빠져나오고
     * 남은 항목은 다음 사이클로 미룬다. 어느 시점에 멈추든 마지막의 단 한 번뿐인 commit 은 그대로
     * 실행되어 이번 사이클에 모은 것은 전부 저장된다.
     */
    static void run(Context context, List<FeedParser.Post> posts, boolean sendNotifications,
                     long deadline) {
        if (!enabled(context)) return;

        SharedPreferences preferences = FeedChecker.prefs(context);
        String nickname = nickname(context);
        boolean myPosts = myPostsEnabled(context);
        boolean myReplies = myRepliesEnabled(context);
        long now = System.currentTimeMillis();

        List<TrackedPosts.Tracked> tracked = TrackedPosts.prune(
                TrackedPosts.decode(preferences.getString(KEY_TRACKED, "")), now);
        Set<String> scanned = stringSet(preferences, KEY_SCANNED);
        Set<String> seen = stringSet(preferences, KEY_SEEN);
        List<TrackedPosts.Tracked> candidates =
                TrackedPosts.decode(preferences.getString(KEY_CANDIDATES, ""));
        List<TrackedPosts.Tracked> probed = new ArrayList<>();
        Set<String> seenNow = new HashSet<>();

        if (myPosts) tracked = registerMyPosts(posts, tracked, scanned, nickname, now, deadline);
        if (myReplies) tracked = discover(posts, tracked, scanned, probed, nickname, now, deadline);
        for (TrackedPosts.Tracked probe : probed) {
            candidates = TrackedPosts.addCandidate(candidates, probe, now);
        }

        List<Hit> fresh = new ArrayList<>();
        for (TrackedPosts.Tracked post : TrackedPosts.due(tracked, CHECK_PER_CYCLE)) {
            // 예산 소진 - checked 를 건드리지 않으므로 이 글은 다음 사이클 대기열 앞쪽에 남는다.
            if (SystemClock.elapsedRealtime() >= deadline) break;
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

            // 이 글을 처음 확인하는 사이클엔 지금 댓글을 기준점으로만 남기고 알리지 않는다.
            // post 는 markChecked 이전 값이라 checked 는 이번 사이클 전 상태 그대로다. 사이클당
            // CHECK_PER_CYCLE 개만 확인하므로 전역 플래그 하나로는 이 시점을 글마다 판단할 수 없다.
            if (post.checked == 0) continue;
            String postTitle = title(context, post.url);
            for (CommentParser.Comment comment : picked) {
                fresh.add(new Hit(comment, post.url, postTitle));
            }
        }

        // 이미 한 번 연 글에 내 댓글이 새로 달렸는지 본다. 재확인 루프 뒤에 둔다 - pick 은 사이클
        // 전의 seen 을 읽으므로 한 사이클에 같은 글을 두 번 보면 같은 답을 두 번 알린다.
        if (myReplies) {
            for (TrackedPosts.Tracked candidate :
                    TrackedPosts.due(TrackedPosts.without(candidates, tracked), RESCAN_PER_CYCLE)) {
                if (SystemClock.elapsedRealtime() >= deadline) break;
                // 받기 전에 확인 처리한다. 계속 실패하는 글이 대기열 맨 앞에 박혀 자리를 먹지 않게.
                candidates = TrackedPosts.markChecked(candidates, candidate.url, now);
                List<CommentParser.Comment> comments;
                try {
                    comments = CommentParser.parseComments(fetchComments(candidate));
                } catch (Exception ignored) {
                    continue;
                }
                if (!mine(comments, nickname)) continue;

                // 이 시점에 찾은 내 댓글은 보통 한 바퀴 안에 쓴 것이라 답도 최근이다. 다만
                // 스위치2를 껐다 켜면 꺼진 동안 달린 답도 여기서 한 번은 알린다 - 두 스위치가
                // 모두 꺼져 있을 때 run() 이 일찍 끝나 아무것도 흡수하지 않는 것과 같은 이치다.
                tracked = TrackedPosts.add(tracked, candidate.url, candidate.postCode,
                        candidate.boardCode, TrackedPosts.MY_COMMENT, now);
                tracked = TrackedPosts.markChecked(tracked, candidate.url, now);
                String postTitle = title(context, candidate.url);
                for (CommentParser.Comment comment : CommentRules.pick(
                        comments, seen, nickname, TrackedPosts.MY_COMMENT, myPosts, myReplies)) {
                    fresh.add(new Hit(comment, candidate.url, postTitle));
                }
                for (CommentParser.Comment comment : comments) seenNow.add(comment.code);
            }
        }

        // 이번 사이클에 본 코드와 전에 본 코드를 합친 뒤, 저장할 때 최신 것만 남긴다.
        seenNow.addAll(seen);

        // setNickname 과 모니터를 공유한다 - 검사와 commit 사이에 닉네임이 바뀌어 끼어들 수 없다.
        synchronized (CommentChecker.class) {
            if (!nickname.equals(nickname(context))) return;
            preferences.edit()
                    .putString(KEY_TRACKED, TrackedPosts.encode(tracked))
                    .putString(KEY_CANDIDATES, TrackedPosts.encode(
                            TrackedPosts.prune(TrackedPosts.without(candidates, tracked), now)))
                    .putStringSet(KEY_SCANNED, TrackedPosts.keepScanned(posts, scanned, now))
                    .putStringSet(KEY_SEEN, CommentRules.newestCodes(seenNow, MAX_SEEN))
                    .commit();
        }

        if (sendNotifications && !fresh.isEmpty()) notifyComments(context, fresh);
    }

    /**
     * RSS 에서 내가 쓴 글을 사이클당 {@link #SCAN_PER_CYCLE} 개까지 열어 추적에 넣는다. 두 코드를
     * 얻으려 글 페이지를 한 번 받는다. 예산이 없으면 discover 와 마찬가지로 다음 사이클로 미룬다 —
     * 그렇지 않으면 아직 추적에 안 들어간 글을 사이클마다 다시 받아 상한(prune)에 밀려난 오래된
     * 추적 글을 계속 몰아낸다.
     */
    private static List<TrackedPosts.Tracked> registerMyPosts(
            List<FeedParser.Post> posts, List<TrackedPosts.Tracked> tracked,
            Set<String> scanned, String nickname, long now, long deadline) {
        int budget = SCAN_PER_CYCLE;
        for (FeedParser.Post post : posts) {
            if (budget <= 0) break;
            if (!nickname.equals(post.author)) continue;
            if (scanned.contains(post.id) || TrackedPosts.contains(tracked, post.url)) continue;
            if (TrackedPosts.outsideWindow(post, now)) continue;
            // 예산 소진 - 아직 scanned 에 넣지 않았으니 다음 사이클에 그대로 다시 시도된다.
            if (SystemClock.elapsedRealtime() >= deadline) break;
            budget--;

            String page;
            try {
                page = get(post.url, MAX_PAGE_BYTES);
            } catch (Exception ignored) {
                continue; // scanned 에 남기지 않아 다음 사이클 예산 안에서 다시 시도한다
            }
            // 댓글이 막힌 게시판처럼 코드가 안 나와도 페이지를 받은 이상 다시 열 필요가 없다.
            // 여기서 표시하지 않으면 그런 글을 사이클마다 다시 받는다.
            scanned.add(post.id);
            tracked = TrackedPosts.add(tracked, post.url,
                    CommentParser.postCode(page), CommentParser.boardCode(page),
                    TrackedPosts.MY_POST, now);
        }
        return tracked;
    }

    /**
     * 아직 안 본 남의 글을 사이클당 {@link #SCAN_PER_CYCLE} 개까지 열어, 내 댓글이 있으면 추적에
     * 넣는다. 있든 없든 {@link #KEY_SCANNED} 에 적어 두 번 열지 않는다. 댓글 목록까지 받은 글은
     * {@code probed} 에 담아 재스캔 후보로 넘긴다.
     */
    private static List<TrackedPosts.Tracked> discover(
            List<FeedParser.Post> posts, List<TrackedPosts.Tracked> tracked,
            Set<String> scanned, List<TrackedPosts.Tracked> probed, String nickname,
            long now, long deadline) {
        int budget = SCAN_PER_CYCLE;
        for (FeedParser.Post post : posts) {
            if (budget <= 0) break;
            // 내 글은 registerMyPosts 몫이다. 여기서 scanned 에 넣으면 그쪽이 이 글을 영영 건너뛴다.
            if (nickname.equals(post.author)) continue;
            if (scanned.contains(post.id) || TrackedPosts.contains(tracked, post.url)) continue;
            if (TrackedPosts.outsideWindow(post, now)) continue;
            // 예산 소진 - 아직 scanned 에 넣지 않았으니 다음 사이클에 그대로 다시 시도된다.
            if (SystemClock.elapsedRealtime() >= deadline) break;
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

            // 예산 소진 - 페이지는 받았지만 아직 댓글을 못 봤으니 scanned 표시를 되돌려 다음
            // 사이클에 이 글을 처음부터 다시 스캔한다.
            if (SystemClock.elapsedRealtime() >= deadline) {
                scanned.remove(post.id);
                break;
            }
            // added 는 게시 시각이다. 재스캔 후보의 만료와 상한이 게시 시각을 기준으로 걸린다.
            TrackedPosts.Tracked probe = new TrackedPosts.Tracked(post.url, postCode, boardCode,
                    post.published > 0 ? post.published : now, now, 0);
            List<CommentParser.Comment> comments;
            try {
                comments = CommentParser.parseComments(fetchComments(probe));
            } catch (Exception ignored) {
                scanned.remove(post.id); // 실패한 글은 다음 사이클에 다시 시도한다
                continue;
            }
            probed.add(probe);
            if (mine(comments, nickname)) {
                tracked = TrackedPosts.add(tracked, post.url, postCode, boardCode,
                        TrackedPosts.MY_COMMENT, now);
            }
        }
        return tracked;
    }

    /** 목록에 이 닉네임으로 쓴 댓글이 있는지. */
    private static boolean mine(List<CommentParser.Comment> comments, String nickname) {
        for (CommentParser.Comment comment : comments) {
            if (nickname.equals(comment.author)) return true;
        }
        return false;
    }

    /**
     * 새 댓글을 알림으로 보낸다. 한 건이거나 모두 한 글에 달렸으면 그 글을, 여러 글에 걸치면 메인을 링크한다.
     * 권한이 없거나 Android 13+ 에서 알림 권한이 거절되면 이 함수를 호출하지 않는다.
     */
    private static void notifyComments(Context context, List<Hit> hits) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        ensureNotificationChannel(context);
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent intent;
        String title;
        String text;
        int notificationId;
        if (hits.size() == 1) {
            Hit hit = hits.get(0);
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(hit.postUrl));
            title = hit.comment.author + " 님의 댓글";
            text = hit.postTitle.isEmpty()
                    ? hit.comment.body
                    : hit.comment.body + " · " + hit.postTitle;
            notificationId = hit.comment.code.hashCode();
        } else {
            // 모두 한 글에 달린 댓글이면 그 글을 연다. 여러 글에 걸치면 목록을 연다.
            String onlyUrl = hits.get(0).postUrl;
            for (Hit hit : hits) {
                if (!hit.postUrl.equals(onlyUrl)) {
                    onlyUrl = null;
                    break;
                }
            }
            intent = onlyUrl != null
                    ? new Intent(Intent.ACTION_VIEW, Uri.parse(onlyUrl))
                    : new Intent(context, MainActivity.class);
            title = "새 댓글 " + hits.size() + "개";
            text = hits.get(0).comment.author + " 외 " + (hits.size() - 1) + "명";
            notificationId = SUMMARY_ID;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL);
        if (hits.size() > 1) {
            Notification.InboxStyle style = new Notification.InboxStyle();
            for (int i = 0; i < Math.min(5, hits.size()); i++) {
                Hit hit = hits.get(i);
                style.addLine(hit.comment.author + ": " + hit.comment.body);
            }
            if (hits.size() > 5) style.setSummaryText("외 " + (hits.size() - 5) + "개");
            builder.setStyle(style).setNumber(hits.size());
        } else {
            builder.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        manager.notify(notificationId, builder.build());
    }
}
