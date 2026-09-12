package com.illusionlive.notifier;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
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
}
