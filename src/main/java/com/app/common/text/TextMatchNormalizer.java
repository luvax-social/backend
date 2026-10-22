package com.app.common.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises Hidden Words entries and content using the shared text match contract.
 *
 * <p>The contract is the "Text match normalisation" section of {@code
 * docs/modules/GLOBAL_RULES.md}. The cases that pin it are {@code
 * src/test/resources/text-match/text-match-vectors.json}, and the frontend mirror is {@code
 * frontend/src/utils/textMatch.ts}; a change to the contract changes all of them together.
 */
public final class TextMatchNormalizer {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}@$!]+");
    private static final Pattern NAME_WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Map<Character, Character> LEET =
            Map.of(
                    '0', 'o',
                    '1', 'i',
                    '3', 'e',
                    '4', 'a',
                    '5', 's',
                    '7', 't',
                    '@', 'a',
                    '$', 's',
                    '!', 'i');

    private TextMatchNormalizer() {}

    public static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        String folded =
                MARKS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFD))
                        .replaceAll("")
                        .replace('đ', 'd')
                        .replace('Đ', 'd')
                        .toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(folded);
        while (matcher.find()) {
            String token = stripMarkers(matcher.group());
            if (!token.isEmpty()) {
                tokens.add(token.codePoints().anyMatch(Character::isLetter) ? leet(token) : token);
            }
        }
        return tokens;
    }

    public static String normalizePhrase(String raw) {
        return String.join(" ", tokens(raw));
    }

    /**
     * Normalises a place name to the key the places registry deduplicates on.
     *
     * <p>Shares the folding steps of the Hidden Words contract (NFD, marks removed, the stroked d
     * mapped to d, lower case) and joins runs of letters and digits with one space, but never maps
     * digits to letters. That step is right for matching hostile spellings and wrong for naming: it
     * would merge "B4 Cafe" with "Ba Cafe". The same contract is implemented in SQL by {@code
     * fn_normalise_place_name} (V153), and a parity test pins the two to the shared vectors.
     *
     * @param raw the place name as typed; may be null
     * @return the normalised key, or an empty string when the name holds no letter or digit
     */
    public static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String folded =
                MARKS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFD))
                        .replaceAll("")
                        .replace('đ', 'd')
                        .replace('Đ', 'd')
                        .toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        Matcher matcher = NAME_WORD.matcher(folded);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return String.join(" ", words);
    }

    private static String stripMarkers(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && token.charAt(start) == '@') {
            start++;
        }
        while (end > start && token.charAt(end - 1) == '!') {
            end--;
        }
        return token.substring(start, end);
    }

    private static String leet(String token) {
        StringBuilder result = new StringBuilder(token.length());
        for (char character : token.toCharArray()) {
            result.append(LEET.getOrDefault(character, character));
        }
        return result.toString();
    }
}
