package com.app.common.text;

/**
 * Shortens user text for display beside a notification or other compact surface.
 *
 * <p>Counts and cuts by Unicode code point, never by {@code char}, so an emoji or any character
 * outside the Basic Multilingual Plane is never split in half. Runs of whitespace, line breaks
 * included, collapse to one space, because a snippet is shown on a single line.
 */
public final class Snippets {

    /** The longest snippet returned, in code points, the ellipsis included. */
    public static final int MAX_CODE_POINTS = 140;

    private static final String ELLIPSIS = "…";

    private Snippets() {}

    /**
     * Returns {@code text} as a single-line snippet of at most {@link #MAX_CODE_POINTS} code
     * points.
     *
     * @return the snippet, or null when {@code text} is null or blank
     */
    public static String of(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        if (flat.isEmpty()) {
            return null;
        }
        if (flat.codePointCount(0, flat.length()) <= MAX_CODE_POINTS) {
            return flat;
        }
        int end = flat.offsetByCodePoints(0, MAX_CODE_POINTS - 1);
        return flat.substring(0, end).stripTrailing() + ELLIPSIS;
    }
}
