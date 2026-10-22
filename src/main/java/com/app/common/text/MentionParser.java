package com.app.common.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code @username} mentions in a caption or comment.
 *
 * <p>The handle grammar is the username grammar of registration: letters, digits, underscore and
 * dot, up to 30 characters. A mention starts at an {@code @} that does not follow a handle
 * character, so the {@code @} inside an email address is not one. Trailing dots are not part of the
 * handle, because a sentence ending in a mention ({@code "thanks @jane.doe."}) must still find
 * {@code jane.doe}.
 */
public final class MentionParser {

    private static final Pattern MENTION =
            Pattern.compile("(?<![A-Za-z0-9_.@])@([A-Za-z0-9_.]{1,30})");

    private MentionParser() {}

    /**
     * Returns the distinct handles mentioned, in order of first appearance.
     *
     * <p>Two spellings that differ only in case are one handle: usernames match case-insensitively.
     * The spelling kept is the first one typed.
     *
     * @param text the caption or comment; may be null
     * @param max the most handles to return
     * @return the handles without the {@code @}, at most {@code max}
     */
    public static List<String> handles(String text, int max) {
        if (text == null || text.isEmpty() || max <= 0) {
            return List.of();
        }
        Map<String, String> byLowerCase = new LinkedHashMap<>();
        Matcher matcher = MENTION.matcher(text);
        while (matcher.find() && byLowerCase.size() < max) {
            String handle = stripTrailingDots(matcher.group(1));
            if (!handle.isEmpty()) {
                byLowerCase.putIfAbsent(handle.toLowerCase(Locale.ROOT), handle);
            }
        }
        return new ArrayList<>(byLowerCase.values());
    }

    private static String stripTrailingDots(String handle) {
        int end = handle.length();
        while (end > 0 && handle.charAt(end - 1) == '.') {
            end--;
        }
        return handle.substring(0, end);
    }
}
