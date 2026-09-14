package com.illusionlive.notifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        if (!valid(url, postCode, boardCode)) return list;

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

    /**
     * 재스캔 후보를 넣는다. {@code item.added} 에는 글 게시 시각을 넣는다 — 발견 스캔은 RSS 를
     * 새 글부터 열기 때문에 연 시각을 쓰면 오래된 글이 최근 것처럼 보여 상한이 새 글부터 자른다.
     * 같은 url 이 이미 있거나 값이 형식에 맞지 않으면 넣지 않는다. 어느 쪽이든 만료와 상한을
     * 다시 적용한다.
     */
    static List<Tracked> addCandidate(List<Tracked> list, Tracked item, long now) {
        List<Tracked> next = new ArrayList<>(list);
        if (valid(item.url, item.postCode, item.boardCode) && !contains(list, item.url)) {
            next.add(item);
        }
        return prune(next, now);
    }

    /** {@code list} 에서 {@code exclude} 에도 있는 url 을 뺀다. 순서는 그대로다. */
    static List<Tracked> without(List<Tracked> list, List<Tracked> exclude) {
        List<Tracked> next = new ArrayList<>();
        for (Tracked item : list) {
            if (!contains(exclude, item.url)) next.add(item);
        }
        return next;
    }

    static boolean contains(List<Tracked> list, String url) {
        for (Tracked item : list) {
            if (item.url.equals(url)) return true;
        }
        return false;
    }

    /** 여는 주소가 https 이고 저장 구분자가 섞일 수 없는 값만 받는다. */
    private static boolean valid(String url, String postCode, String boardCode) {
        return url != null && url.startsWith("https://")
                && url.indexOf(UNIT) < 0 && url.indexOf(RECORD) < 0
                && CODE.matcher(postCode).matches() && postCode.charAt(0) == 'p'
                && CODE.matcher(boardCode).matches() && boardCode.charAt(0) == 'b';
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

    /** 게시한 지 {@link #TTL_MS} 가 지났는지. 날짜를 읽지 못한 글(0)은 창 안에 있는 것으로 본다. */
    static boolean outsideWindow(FeedParser.Post post, long now) {
        return post.published > 0 && now - post.published >= TTL_MS;
    }

    /**
     * 연 적 있는 글 가운데 다시 볼 일이 남은 것 — 아직 캐시에 있고 창 안인 글 — 만 남긴다. 개수
     * 상한으로 자르면 해시 순서로 창 안의 글이 밀려나, 발견 스캔이 그 글을 사이클마다 다시 받는다.
     */
    static Set<String> keepScanned(List<FeedParser.Post> posts, Set<String> scanned, long now) {
        Set<String> keep = new HashSet<>();
        for (FeedParser.Post post : posts) {
            if (scanned.contains(post.id) && !outsideWindow(post, now)) keep.add(post.id);
        }
        return keep;
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
