package com.illusionlive.notifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Member palette. Mirrors IllusionLiveNotifier/Assets/board-colors.json — the desktop app reads
 * that file, this app ships no assets, so the table lives here as well. Keep the two in sync.
 * A member may carry two colours; only the first is painted as the name's text colour.
 */
final class MemberColors {
    private static final double MIN_CONTRAST = 4.5;
    private static final double MIN_SATURATION = 0.45;

    private static final Map<String, int[]> TABLE = new HashMap<String, int[]>();

    static {
        put("유메루", 0xFFEDE5FC);
        put("도라리", 0xFF7D8BE4, 0xFFFFEAC9);
        put("위즐리어카", 0xFFFFD392);
        put("코메", 0xFF413C40);
        put("이비 EB", 0xFFBD8392);
        put("쿠모리 키피", 0xFF7DAA64);
        put("소히", 0xFFE9C4BC);
        put("디롬", 0xFF9F1D4C);
        put("리이", 0xFFFFCFCB);
        put("후유노", 0xFFBAB3DC);
        put("루스티카나", 0xFFD7C7F4);
        put("서몽", 0xFFFAD375, 0xFF7BB4BD);
        put("청예솔", 0xFFFDF3EA);
        put("냐루", 0xFFD0FAFF);
    }

    private MemberColors() {
    }

    /** Null for 공식 · 비비 · 기타 and anything new: those keep the neutral label colour. */
    static int[] of(String group) {
        return TABLE.get(group);
    }

    /**
     * The member colour as text on the given surface. Half the palette is near-white (청예솔
     * #FDF3EA) and unreadable as text. Darkening alone turns those into mud, so saturation is
     * lifted first and only then is the colour dimmed to 4.5:1 — hue never moves. 코메 and 디롬
     * already carry enough contrast and come back untouched. On the dark theme's canvas the move
     * goes the other way: there is no room below, so the dark members are lifted toward white
     * instead and saturation is left alone (raising it only darkens).
     */
    static int readableOn(int background, int color) {
        double surface = luminance(background);
        if (contrast(surface, luminance(color)) >= MIN_CONTRAST) return color;
        boolean lift = contrast(surface, 1.0) > contrast(surface, 0.0);
        int ink = lift ? color : saturate(color);
        for (int step = 0; step < 40 && contrast(surface, luminance(ink)) < MIN_CONTRAST; step++)
            ink = lift ? lighten(ink) : darken(ink);
        return ink;
    }

    /**
     * Ink for text laid on a member's colour at full strength — the settings group header band.
     * White or the canvas ink, whichever carries further; every member in the table clears 5.7:1
     * one way or the other, so none of {@link #readableOn}'s walking is needed here. This is why
     * the band shows the true colour where the old chip could only show a darkened echo of it.
     */
    static int inkOn(int fill) {
        double surface = luminance(fill);
        return contrast(surface, 1.0) >= contrast(surface, luminance(0xFF15161C))
                ? 0xFFFFFFFF : 0xFF15161C;
    }

    private static void put(String group, int... colors) {
        TABLE.put(group, colors);
    }

    /**
     * Pulls the channels away from the brightest one, which raises saturation while leaving every
     * channel's share of (max - value) — the hue — exactly where it was.
     */
    private static int saturate(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        if (max == min) return color; // a pure grey has no hue to keep
        if ((max - min) / (double) max >= MIN_SATURATION) return color;
        double spread = max * MIN_SATURATION / (max - min);
        return 0xFF000000
                | (channelToward(max, red, spread) << 16)
                | (channelToward(max, green, spread) << 8)
                | channelToward(max, blue, spread);
    }

    private static int channelToward(int max, int value, double spread) {
        return Math.max(0, Math.min(255, (int) Math.round(max - (max - value) * spread)));
    }

    /** Ceiling, so a channel sitting at 0 still moves and the loop always terminates. */
    private static int lighten(int color) {
        return 0xFF000000 | (toward255((color >> 16) & 0xFF) << 16)
                | (toward255((color >> 8) & 0xFF) << 8) | toward255(color & 0xFF);
    }

    private static int toward255(int value) {
        return value + (int) Math.ceil((255 - value) * 0.08);
    }

    private static int darken(int color) {
        int red = (int) (((color >> 16) & 0xFF) * 0.92);
        int green = (int) (((color >> 8) & 0xFF) * 0.92);
        int blue = (int) ((color & 0xFF) * 0.92);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static double contrast(double one, double other) {
        return (Math.max(one, other) + 0.05) / (Math.min(one, other) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * channel(color >> 16) + 0.7152 * channel(color >> 8) + 0.0722 * channel(color);
    }

    private static double channel(int shifted) {
        double value = (shifted & 0xFF) / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
