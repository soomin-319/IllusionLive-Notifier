package com.illusionlive.notifier;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

final class FeedParser {
    // ASCII record/unit separators: parseItem drops a post whose slug carries one and strips them
    // from the text fields, so nothing written here can hold them and no escaping is needed.
    // ponytail: switch to JSON if a field ever has to keep a control character.
    private static final char UNIT = 0x1f;
    private static final char RECORD = 0x1e;
    /** A board slug is a short path segment; anything longer is the feed padding the board list. */
    private static final int MAX_SLUG_LENGTH = 64;

    private static final Comparator<Post> NEWEST_FIRST = new Comparator<Post>() {
        @Override public int compare(Post left, Post right) {
            return Long.compare(right.published, left.published);
        }
    };

    private FeedParser() {}

    static final class Post {
        final String id;
        final String boardSlug;
        final String title;
        final String author;
        final long published;
        final String url;

        Post(String id, String boardSlug, String title, String author, long published, String url) {
            this.id = id;
            this.boardSlug = boardSlug;
            this.title = title;
            this.author = author;
            this.published = published;
            this.url = url;
        }
    }

    static List<Post> parse(byte[] xml) throws Exception {
        if (hasDoctype(xml)) throw new IllegalArgumentException("DOCTYPE is not allowed");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        // Android's DocumentBuilderFactory throws on every one of these names, so they harden the
        // desktop JAXP parser the self-test runs on and nothing else. hasDoctype() above is the
        // defence that actually holds on the device — keep it working on its own.
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);

        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList items = document.getElementsByTagName("item");
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Post post = parseItem((Element) items.item(i));
            if (post != null) posts.add(post);
        }
        Collections.sort(posts, NEWEST_FIRST);
        return posts;
    }

    /** Newest first, one entry per id; a re-fetched post wins over its cached copy. */
    static List<Post> merge(List<Post> fresh, List<Post> cached, int max) {
        LinkedHashMap<String, Post> byId = new LinkedHashMap<>();
        for (Post post : fresh) byId.put(post.id, post);
        for (Post post : cached) if (!byId.containsKey(post.id)) byId.put(post.id, post);
        List<Post> merged = new ArrayList<>(byId.values());
        Collections.sort(merged, NEWEST_FIRST);
        return merged.size() <= max ? merged : new ArrayList<>(merged.subList(0, max));
    }

    static String encode(List<Post> posts) {
        StringBuilder text = new StringBuilder();
        for (Post post : posts) {
            if (text.length() > 0) text.append(RECORD);
            text.append(post.id).append(UNIT).append(post.boardSlug).append(UNIT)
                    .append(post.title).append(UNIT).append(post.author).append(UNIT)
                    .append(post.published).append(UNIT).append(post.url);
        }
        return text.toString();
    }

    static List<Post> decode(String text) {
        List<Post> posts = new ArrayList<>();
        if (text == null || text.isEmpty()) return posts;
        for (String row : text.split(String.valueOf(RECORD), -1)) {
            String[] parts = row.split(String.valueOf(UNIT), -1);
            if (parts.length != 6) continue;
            // parseItem only ever stores an https illusionlive.com link, but nothing re-checks that
            // once a row has been through storage, and MainActivity hands the url straight to
            // ACTION_VIEW. Re-assert the cheap half of it here so the cache cannot widen it.
            if (!parts[5].startsWith("https://")) continue;
            try {
                posts.add(new Post(parts[0], parts[1], parts[2], parts[3],
                        Long.parseLong(parts[4]), parts[5]));
            } catch (NumberFormatException ignored) {}
        }
        return posts;
    }

    private static Post parseItem(Element item) {
        String link = value(item, "link").trim();
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
                    !(host.equalsIgnoreCase("illusionlive.com") || host.equalsIgnoreCase("www.illusionlive.com")) ||
                    !"view".equalsIgnoreCase(queryValue(uri.getRawQuery(), "bmode"))) return null;

            String path = uri.getPath() == null ? "" : uri.getPath();
            path = path.replaceAll("^/+|/+$", "");
            if (!usableSlug(path)) return null;

            String title = clean(value(item, "title"));
            if (title.isEmpty()) title = "(제목 없음)";
            String author = clean(value(item, "author"));
            if (author.isEmpty()) author = clean(value(item, "creator"));
            String id = clean(value(item, "guid"));
            if (id.isEmpty()) id = link;
            return new Post(id, path, title, author, parseDate(value(item, "pubDate")), link);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * {@link URI#getPath()} percent-decodes, so a link can hand us a slug holding {@link #UNIT} or
     * {@link #RECORD}. {@link #encode} writes those out raw, which shifts the row boundaries and
     * takes neighbouring posts down on decode; an over-long slug instead pads the board list.
     * Neither belongs in a board name, so the post goes rather than the storage format changing.
     */
    private static boolean usableSlug(String slug) {
        if (slug.isEmpty() || slug.length() > MAX_SLUG_LENGTH) return false;
        for (int i = 0; i < slug.length(); i++) {
            char c = slug.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    /** Same reasoning for the fields kept rather than dropped: shown text loses its controls. */
    private static String clean(String text) {
        return text.replaceAll("\\p{Cntrl}", " ").trim();
    }

    private static String value(Element parent, String wanted) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            String name = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
            int colon = name.indexOf(':');
            if (colon >= 0) name = name.substring(colon + 1);
            if (wanted.equalsIgnoreCase(name)) return child.getTextContent() == null ? "" : child.getTextContent();
        }
        return "";
    }

    private static String queryValue(String query, String wanted) throws Exception {
        if (query == null) return "";
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], "UTF-8");
            if (wanted.equalsIgnoreCase(key))
                return parts.length == 2 ? URLDecoder.decode(parts[1], "UTF-8") : "";
        }
        return "";
    }

    private static long parseDate(String text) {
        String[] patterns = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
                format.setLenient(true);
                Date parsed = format.parse(text.trim());
                if (parsed != null) return parsed.getTime();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    /**
     * Byte scan rather than a decode: the declared encoding cannot be trusted before parsing, and
     * dropping NUL bytes first makes the UTF-16 spelling of the same declaration match too.
     *
     * <p>Only the prolog is walked. A DOCTYPE may not follow the root element, so stopping there
     * still catches every real declaration while leaving a post that merely quotes the string —
     * a CDATA title, say — to parse like any other.
     */
    static boolean hasDoctype(byte[] xml) {
        String text = new String(xml, StandardCharsets.ISO_8859_1).replace("\0", "");
        int at = 0;
        while (at < text.length()) {
            char c = text.charAt(at);
            // whitespace, and the stray high bytes a byte-order mark leaves behind, declare nothing
            if (c > 0x7f || Character.isWhitespace(c)) { at++; continue; }
            if (text.regionMatches(true, at, "<!DOCTYPE", 0, 9)) return true;
            String close;
            if (text.startsWith("<!--", at)) close = "-->";
            else if (text.startsWith("<?", at)) close = "?>";
            else return false; // the root element starts here, and the prolog is over
            int end = text.indexOf(close, at + 2);
            if (end < 0) return false; // unterminated: the parse below rejects the document anyway
            at = end + close.length();
        }
        return false;
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try { factory.setFeature(name, value); } catch (Exception ignored) {}
    }
}
