package com.app.modules.message.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a string is exactly one emoji, which is what a message reaction may be.
 *
 * <p>The unit is the extended grapheme cluster, so a family joined by zero-width joiners, a skin
 * tone, a flag, a keycap and a variation-selector form are each one emoji, while two emoji, an
 * emoji followed by text, a bare digit and plain text are not. A symbol that is also a text
 * character ({@code U+2764}) counts only with the emoji variation selector ({@code U+FE0F}), which
 * is how the picker emits it, so a typed lookalike is refused.
 */
public final class ReactionEmojiValidator {

    /** The column holds {@code VARCHAR(32)}; longer input cannot be a stored reaction. */
    private static final int MAX_LENGTH = 32;

    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    // A keycap is a digit, # or * (with an optional emoji variation selector) joined to U+20E3.
    // The enclosing mark alone is not enough: it attaches to any base, so "a" + U+20E3 is one
    // grapheme that is not an emoji.
    private static final Pattern KEYCAP = Pattern.compile("[0-9#*]\\x{FE0F}?\\x{20E3}");

    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int BLACK_FLAG = 0x1F3F4;
    private static final int FIRST_TAG = 0xE0020;
    private static final int LAST_TAG = 0xE007E;
    private static final int CANCEL_TAG = 0xE007F;

    // Real sequences join at most a family of four people and a heart, which is four joins.
    private static final int MAX_JOINS = 4;

    private ReactionEmojiValidator() {}

    /**
     * Reports whether the text is exactly one emoji grapheme cluster.
     *
     * @param s the candidate text, which may be null
     * @return true for a single emoji, false for null, empty, text, or more than one grapheme
     */
    public static boolean isSingleEmoji(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_LENGTH) {
            return false;
        }
        Matcher cluster = GRAPHEME.matcher(s);
        if (!cluster.find() || cluster.end() != s.length()) {
            return false;
        }
        return KEYCAP.matcher(s).matches()
                || isFlag(s)
                || isSubdivisionFlag(s)
                || isJoinedSequence(s);
    }

    private static boolean isFlag(String s) {
        int[] codePoints = s.codePoints().toArray();
        return codePoints.length == 2
                && isRegionalIndicator(codePoints[0])
                && isRegionalIndicator(codePoints[1]);
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    // The black flag followed by lower-case tag letters and the cancel tag, for example England.
    // Tag characters are accepted nowhere else, so they cannot be stacked behind another emoji.
    private static boolean isSubdivisionFlag(String s) {
        int[] codePoints = s.codePoints().toArray();
        if (codePoints.length < 3
                || codePoints[0] != BLACK_FLAG
                || codePoints[codePoints.length - 1] != CANCEL_TAG) {
            return false;
        }
        for (int i = 1; i < codePoints.length - 1; i++) {
            if (codePoints[i] < FIRST_TAG || codePoints[i] > LAST_TAG) {
                return false;
            }
        }
        return true;
    }

    // One or more pictographic elements joined by zero-width joiners, with no other code point
    // anywhere: no trailing or doubled joiner, no stacked selectors and no marks.
    private static boolean isJoinedSequence(String s) {
        int[] codePoints = s.codePoints().toArray();
        int index = 0;
        int joins = 0;
        while (true) {
            index = consumeElement(codePoints, index);
            if (index < 0) {
                return false;
            }
            if (index == codePoints.length) {
                return true;
            }
            if (codePoints[index] != ZERO_WIDTH_JOINER || ++joins > MAX_JOINS) {
                return false;
            }
            index++;
        }
    }

    // An element is an Extended_Pictographic code point with at most one skin tone or emoji
    // variation selector, and it must render as an emoji: by default, through the selector, or
    // through the skin tone. Returns the index after the element, or -1.
    private static int consumeElement(int[] codePoints, int start) {
        if (start >= codePoints.length || !Character.isExtendedPictographic(codePoints[start])) {
            return -1;
        }
        int next = start + 1;
        boolean decorated = false;
        if (next < codePoints.length
                && (codePoints[next] == VARIATION_SELECTOR_16
                        || Character.isEmojiModifier(codePoints[next]))) {
            decorated = true;
            next++;
        }
        return decorated || Character.isEmojiPresentation(codePoints[start]) ? next : -1;
    }
}
