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
        if (KEYCAP.matcher(s).matches()) {
            return true;
        }
        int first = s.codePointAt(0);
        boolean flag = first >= 0x1F1E6 && first <= 0x1F1FF && s.codePointCount(0, s.length()) == 2;
        boolean pictographic = s.codePoints().anyMatch(Character::isExtendedPictographic);
        boolean presentation =
                Character.isEmojiPresentation(first) || s.indexOf(VARIATION_SELECTOR_16) >= 0;
        return flag || (pictographic && presentation);
    }
}
