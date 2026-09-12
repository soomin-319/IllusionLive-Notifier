package com.illusionlive.notifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 댓글을 지켜볼 글 목록. 한 글은 내가 썼거나({@link #MY_POST}) 내가 댓글을 달았거나
 * ({@link #MY_COMMENT}) 둘 다여서 들어온다.
 *
 * <p>Android API 를 쓰지 않는다. {@code SelfTest} 가 android.jar 없이 컴파일한다.
 *
 * <p>측정한 바로 댓글의 100% 가 글이 올라온 뒤 이틀 안에 달리므로 {@link #TTL_MS} 를 3일로 둔다.
 * 상한 {@link #MAX} 는 그 안에서도 목록이 무한정 길어지지 않게 막는 두 번째 울타리다.
 */
final class TrackedPosts {
    static final int MY_POST = 1;
    static final int MY_COMMENT = 2;
    static final int MAX = 20;
    static final long TTL_MS = 3L * 24 * 60 * 60 * 1000;

    private static final char UNIT = 0x1f;
    private static final char RECORD = 0x1e;
    private static final Pattern CODE = Pattern.compile("^[cpbm][0-9a-f]{1,40}$");
    /** 등록이 최근인 것부터. 상한을 넘기면 뒤쪽이 잘린다. */
    private static final Comparator<Tracked> NEWEST_FIRST = new Comparator<Tracked>() {
        @Override public int compare(Tracked left, Tracked right) {
            return Long.compare(right.added, left.added);
        }
    };
    /** 확인한 지 오래된 것부터. 라운드로빈 순서다. */
    private static final Comparator<Tracked> STALEST_FIRST = new Comparator<Tracked>() {
        @Override public int compare(Tracked left, Tracked right) {
            return Long.compare(left.checked, right.checked);
        }
    };

    private TrackedPosts() {}

    static final class Tracked {
        final String url;
        final String postCode;
        final String boardCode;
        final long added;
        final long checked;
        final int reason;

        Tracked(String url, String postCode, String boardCode, long added, long checked, int reason) {
            this.url = url;
            this.postCode = postCode;
            this.boardCode = boardCode;
            this.added = added;
            this.checked = checked;
            this.reason = reason;
        }
    }

    /**
     * 이미 있는 글이면 사유만 합치고, 없으면 넣는다. 어느 쪽이든 만료와 상한을 다시 적용한다.
     * 값이 형식에 맞지 않으면 목록을 그대로 돌려준다.
     */
    static List<Tracked> add(List<Tracked> list, String url, String postCode, String boardCode,
                             int reason, long now) {
        if (url == null || !url.startsWith("https://") || url.indexOf(UNIT) >= 0
                || url.indexOf(RECORD) >= 0) return list;
        if (!CODE.matcher(postCode).matches() || postCode.charAt(0) != 'p') return list;
        if (!CODE.matcher(boardCode).matches() || boardCode.charAt(0) != 'b') return list;

        List<Tracked> next = new ArrayList<>();
        boolean merged = false;
        for (Tracked item : list) {
            if (item.url.equals(url)) {
                next.add(new Tracked(item.url, item.postCode, item.boardCode,
                        item.added, item.checked, item.reason | reason));
                merged = true;
            } else {
                next.add(item);
            }
        }
        // checked 를 0 으로 두면 새 글이 다음 사이클의 확인 대기열 맨 앞에 선다.
        if (!merged) next.add(new Tracked(url, postCode, boardCode, now, 0L, reason));
        return prune(next, now);
    }

    /** 만료된 항목을 버리고 등록이 최근인 것부터 {@link #MAX} 개만 남긴다. */
    static List<Tracked> prune(List<Tracked> list, long now) {
        List<Tracked> next = new ArrayList<>();
        for (Tracked item : list) {
            if (now - item.added < TTL_MS) next.add(item);
        }
        Collections.sort(next, NEWEST_FIRST);
        return next.size() <= MAX ? next : new ArrayList<>(next.subList(0, MAX));
    }

    /** 확인한 지 오래된 것부터 최대 {@code limit} 개. */
    static List<Tracked> due(List<Tracked> list, int limit) {
        List<Tracked> queue = new ArrayList<>(list);
        Collections.sort(queue, STALEST_FIRST);
        return queue.size() <= limit ? queue : new ArrayList<>(queue.subList(0, limit));
    }

    static List<Tracked> markChecked(List<Tracked> list, String url, long now) {
        List<Tracked> next = new ArrayList<>();
        for (Tracked item : list) {
            next.add(item.url.equals(url)
                    ? new Tracked(item.url, item.postCode, item.boardCode, item.added, now, item.reason)
                    : item);
        }
        return next;
    }

    static String encode(List<Tracked> list) {
        StringBuilder text = new StringBuilder();
        for (Tracked item : list) {
            if (text.length() > 0) text.append(RECORD);
            text.append(item.url).append(UNIT).append(item.postCode).append(UNIT)
                    .append(item.boardCode).append(UNIT).append(item.added).append(UNIT)
                    .append(item.checked).append(UNIT).append(item.reason);
        }
        return text.toString();
    }

    static List<Tracked> decode(String text) {
        List<Tracked> list = new ArrayList<>();
        if (text == null || text.isEmpty()) return list;
        for (String row : text.split(String.valueOf(RECORD), -1)) {
            String[] parts = row.split(String.valueOf(UNIT), -1);
            if (parts.length != 6) continue;
            // 저장을 거친 값은 다시 확인한다. 캐시가 검사 범위를 넓힐 수 없게.
            if (!parts[0].startsWith("https://")) continue;
            if (!CODE.matcher(parts[1]).matches() || parts[1].charAt(0) != 'p') continue;
            if (!CODE.matcher(parts[2]).matches() || parts[2].charAt(0) != 'b') continue;
            try {
                list.add(new Tracked(parts[0], parts[1], parts[2],
                        Long.parseLong(parts[3]), Long.parseLong(parts[4]),
                        Integer.parseInt(parts[5])));
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }
}
