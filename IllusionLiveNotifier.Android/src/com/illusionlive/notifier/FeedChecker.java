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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class FeedChecker {
    static final String FEED_URL = "https://www.illusionlive.com/rss";
    static final String PREFS_NAME = "settings";
    static final String KEY_BACKGROUND = "background_enabled";
    static final String KEY_DYNAMIC_BOARDS = "dynamic_boards";
    static final String KEY_DARK = "dark_mode";
    static final String KEY_TUTORIAL_SEEN = "tutorial_seen";
    static final String KEY_BATTERY_ASKED = "battery_exemption_asked";
    static final String KEY_CACHED_POSTS = "cached_posts";
    // Every install polls the site directly and the feed cannot be cached: the response carries no
    // Last-Modified, ETag or Cache-Control, and sending If-Modified-Since still returns a full 200
    // rather than a 304 (measured 2026-08-21). Each check therefore costs the site a fresh render,
    // about 4 KB gzipped, so the period is what keeps that load down.
    //
    // Doze already stretches the screen-off period to roughly one fire per 9 minutes, so five
    // minutes only changes the screen-on case: about 290 requests per user per day instead of the
    // 1,440 a one minute period would allow. New posts arrive minutes apart at worst.
    static final int POLL_MINUTES = 5;

    private static final int MAX_CACHED_POSTS = 200;
    private static final String CHANNEL_ID = "new_posts";
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    interface Listener { void onComplete(Result result); }

    static final class Result {
        final List<FeedParser.Post> posts;
        final int newCount;
        final int matchedCount;
        final boolean seeded;
        final boolean busy;
        final String error;

        Result(List<FeedParser.Post> posts, int newCount, int matchedCount,
               boolean seeded, boolean busy, String error) {
            this.posts = posts;
            this.newCount = newCount;
            this.matchedCount = matchedCount;
            this.seeded = seeded;
            this.busy = busy;
            this.error = error;
        }
    }

    private FeedChecker() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Posts kept from earlier fetches, so the list still fills without a connection. */
    static List<FeedParser.Post> cachedPosts(Context context) {
        return FeedParser.decode(prefs(context).getString(KEY_CACHED_POSTS, ""));
    }

    static boolean backgroundEnabled(Context context) {
        return prefs(context).getBoolean(KEY_BACKGROUND, true);
    }

    static String boardEnabledKey(String slug) { return "board_enabled_" + slug; }

    static boolean boardEnabled(SharedPreferences preferences, String slug) {
        return preferences.getBoolean(boardEnabledKey(slug), !"65".equalsIgnoreCase(slug));
    }

    static void ensureNotificationChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "새 글 알림", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("일루전 라이브 새 게시글");
        manager.createNotificationChannel(channel);
    }

    static void check(Context context, boolean sendNotifications, Listener listener) {
        final Context app = context.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) {
            deliver(listener, new Result(Collections.<FeedParser.Post>emptyList(), 0, 0,
                    false, true, null));
            return;
        }

        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                Result result;
                try {
                    List<FeedParser.Post> posts = fetch();
                    result = process(app, posts, sendNotifications);
                } catch (Exception error) {
                    prefs(app).edit().putString("last_error", message(error)).commit();
                    result = new Result(Collections.<FeedParser.Post>emptyList(), 0, 0,
                            false, false, message(error));
                } finally {
                    RUNNING.set(false);
                }
                deliver(listener, result);
            }
        });
    }

    private static List<FeedParser.Post> fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(FEED_URL).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(20_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "IllusionLiveNotifier-Android/1.0 (+https://www.illusionlive.com)");
        connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("RSS HTTP " + status);
            try (InputStream input = connection.getInputStream()) {
                return FeedParser.parse(readLimited(input));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Result process(Context context, List<FeedParser.Post> posts, boolean sendNotifications) {
        SharedPreferences preferences = prefs(context);
        boolean initialized = preferences.getBoolean("initialized", false);
        Set<String> seen = new HashSet<>(preferences.getStringSet("seen_ids", Collections.<String>emptySet()));
        List<FeedParser.Post> fresh = new ArrayList<>();

        for (FeedParser.Post post : posts) {
            if (initialized && !seen.contains(post.id)) fresh.add(post);
        }

        List<FeedParser.Post> matched = new ArrayList<>();
        for (FeedParser.Post post : fresh) {
            if (boardEnabled(preferences, post.boardSlug)) matched.add(post);
        }

        LinkedHashSet<String> nextSeen = new LinkedHashSet<>();
        for (FeedParser.Post post : posts) nextSeen.add(post.id);
        for (String id : seen) {
            if (nextSeen.size() >= 500) break;
            nextSeen.add(id);
        }

        List<FeedParser.Post> merged = FeedParser.merge(posts, cachedPosts(context), MAX_CACHED_POSTS);

        // Derived from the capped cache rather than accumulated across checks: a board a tampered
        // feed invented leaves the settings list once its posts age out, and the list can never
        // grow past MAX_CACHED_POSTS entries. Slug length and characters are capped in parseItem.
        Set<String> boards = new HashSet<>();
        for (FeedParser.Post post : merged) boards.add(post.boardSlug);

        preferences.edit()
                .putBoolean("initialized", true)
                .putStringSet("seen_ids", new HashSet<>(nextSeen))
                .putStringSet(KEY_DYNAMIC_BOARDS, boards)
                .putString(KEY_CACHED_POSTS, FeedParser.encode(merged))
                .putLong("last_check", System.currentTimeMillis())
                .remove("last_error")
                .commit();

        if (initialized && sendNotifications && !matched.isEmpty()) notifyPosts(context, matched);
        return new Result(merged, fresh.size(), matched.size(), !initialized, false, null);
    }

    private static void notifyPosts(Context context, List<FeedParser.Post> posts) {
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return;

        ensureNotificationChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent intent;
        String title;
        String text;
        int notificationId;

        if (posts.size() == 1) {
            FeedParser.Post post = posts.get(0);
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(post.url));
            title = post.title;
            text = post.boardSlug + (post.author.isEmpty() ? "" : " · " + post.author);
            notificationId = post.id.hashCode();
        } else {
            intent = new Intent(context, MainActivity.class);
            title = "새 글 " + posts.size() + "개";
            text = posts.get(0).title + " 외 " + (posts.size() - 1) + "개";
            notificationId = 8702;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_SOCIAL);
        if (posts.size() > 1) {
            Notification.InboxStyle style = new Notification.InboxStyle();
            for (int i = 0; i < Math.min(5, posts.size()); i++) style.addLine(posts.get(i).title);
            if (posts.size() > 5) style.setSummaryText("외 " + (posts.size() - 5) + "개");
            builder.setStyle(style).setNumber(posts.size());
        } else {
            builder.setStyle(new Notification.BigTextStyle().bigText(text));
        }
        manager.notify(notificationId, builder.build());
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_FEED_BYTES) throw new IOException("RSS is larger than 2 MB");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String message(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static void deliver(Listener listener, Result result) {
        if (listener == null) return;
        try { listener.onComplete(result); } catch (Exception ignored) {}
    }
}
