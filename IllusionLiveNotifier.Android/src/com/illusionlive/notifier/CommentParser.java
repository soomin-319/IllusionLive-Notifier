package com.illusionlive.notifier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 아임웹 댓글 응답을 읽는다. {@code /ajax/post_comment_paging.cm} 이 돌려주는 JSON 의 html 필드가
 * 입력이고, 글 페이지 HTML 에서 그 호출에 필요한 두 코드를 뽑는 일도 여기서 한다.
 *
 * <p>Android API 를 쓰지 않는다. 이 클래스는 {@code SelfTest} 가 android.jar 없이 컴파일한다.
 *
 * <p>정규식으로 읽는다. 사이트가 마크업을 바꾸면 빈 목록이 나오고 알림이 조용히 멈춘다. 문서를
 * 파싱해도 같은 위험이 남고, 이 응답에는 태그 구조보다 클래스 이름이 더 안정적이다.
 */
final class CommentParser {
    /** 댓글·글·게시판·회원 코드의 공통 형태. 저장 구분자가 섞여 들어올 길을 막는다. */
    private static final Pattern CODE = Pattern.compile("^[cpbm][0-9a-f]{1,40}$");
    private static final Pattern BLOCK =
            Pattern.compile("<div class=\"comment\" id=\"[^\"]*\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHOR =
            Pattern.compile("<div class=\"write\">(.*?)<span", Pattern.DOTALL);
    private static final Pattern MEMBER = Pattern.compile("_comment_body_(m[0-9a-f]+)");
    private static final Pattern BODY = Pattern.compile(
            "comment_body_code=\"([^\"]*)\"\\s*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]*>", Pattern.DOTALL);
    /** 알림 한 줄에 들어갈 만큼만 남긴다. */
    private static final int MAX_BODY = 120;
    /** 모든 댓글의 본문 span 바로 뒤에 붙는 툴바. 여기서 자르면 span 이 안 닫혀도 뒤 마크업을
     *  물지 않는다. 마커가 없으면(마크업이 바뀌면) 블록 전체를 그대로 써서 오늘과 같은 동작으로
     *  물러난다. */
    private static final String BODY_END_MARKER = "class=\"tools";
    /** 댓글 폼 ID. 숨은 입력 필드 코드는 이 폼 안에만 있으므로 이전 마크업의 decoy 를 피한다. */
    private static final String FORM_MARKER = "id=\"comment_form\"";
    private static final Pattern POST_CODE = hidden("post_code");
    private static final Pattern BOARD_CODE = hidden("board_code");

    private CommentParser() {}

    static final class Comment {
        final String code;
        final String author;
        final String member;
        final String body;

        Comment(String code, String author, String member, String body) {
            this.code = code;
            this.author = author;
            this.member = member;
            this.body = body;
        }
    }

    /** 화면에 보이는 순서 그대로. 코드가 형식에 맞지 않는 댓글은 버린다. */
    static List<Comment> parseComments(String html) {
        List<Comment> comments = new ArrayList<>();
        if (html == null || html.isEmpty()) return comments;

        Matcher block = BLOCK.matcher(html);
        List<Integer> starts = new ArrayList<>();
        while (block.find()) starts.add(block.start());

        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : html.length();
            Comment comment = parseBlock(html.substring(from, to));
            if (comment != null) comments.add(comment);
        }
        return comments;
    }

    /** 글 페이지의 댓글 폼에 박혀 있는 글 코드. 없거나 형식이 틀리면 빈 문자열. */
    static String postCode(String pageHtml) { return code(pageHtml, POST_CODE, 'p'); }

    /** 같은 폼의 게시판 코드. 댓글 조회에 두 값이 모두 필요하다. */
    static String boardCode(String pageHtml) { return code(pageHtml, BOARD_CODE, 'b'); }

    private static String code(String pageHtml, Pattern pattern, char prefix) {
        if (pageHtml == null) return "";
        int formAt = pageHtml.indexOf(FORM_MARKER);
        if (formAt < 0) return "";
        Matcher matcher = pattern.matcher(pageHtml.substring(formAt));
        if (!matcher.find()) return "";
        String value = matcher.group(1);
        if (value.isEmpty() || value.charAt(0) != prefix) return "";
        return CODE.matcher(value).matches() ? value : "";
    }

    private static Pattern hidden(String name) {
        return Pattern.compile("name=\"" + name + "\"\\s+value=\"([^\"]*)\"");
    }

    private static Comment parseBlock(String block) {
        int toolsAt = block.indexOf(BODY_END_MARKER);
        String bodyRegion = toolsAt < 0 ? block : block.substring(0, toolsAt);
        Matcher body = BODY.matcher(bodyRegion);
        if (!body.find()) return null;
        String code = body.group(1);
        if (!CODE.matcher(code).matches()) return null;

        Matcher author = AUTHOR.matcher(block);
        String nick = author.find() ? clean(strip(author.group(1)), 60) : "";

        Matcher member = MEMBER.matcher(block);
        String memberCode = member.find() && CODE.matcher(member.group(1)).matches()
                ? member.group(1) : "";

        return new Comment(code, nick, memberCode, clean(strip(body.group(2)), MAX_BODY));
    }

    /** 태그를 공백으로 바꾼다. {@code <br>} 로 나뉜 줄이 붙어 버리지 않게 지우지 않고 바꾼다. */
    private static String strip(String html) {
        return TAG.matcher(html).replaceAll(" ");
    }

    /** 엔티티를 풀고, 제어문자와 연속 공백을 한 칸으로 줄이고, 길이를 자른다. */
    private static String clean(String text, int max) {
        String value = text
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
        value = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return value.length() <= max ? value : value.substring(0, max);
    }
}
