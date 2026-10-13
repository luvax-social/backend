package com.app.common.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Matches normalised Hidden Words entries against contiguous content tokens.
 *
 * <p>The matching contract is the "Text match normalisation" section of {@code
 * docs/modules/GLOBAL_RULES.md}. The cases that pin it are {@code
 * src/test/resources/text-match/text-match-vectors.json}, and the frontend mirror is {@code
 * frontend/src/utils/textMatch.ts}; a change to the contract changes all of them together.
 *
 * <p>{@link #compile} trusts its input to be the output of {@link
 * TextMatchNormalizer#normalizePhrase}: a raw phrase with a double space would split into an empty
 * token that no content token equals, so the entry would silently never match.
 */
public final class PhraseMatcher {
    private final Set<String> words;
    private final List<List<String>> phrases;
    private final List<PhraseMatcher> alternatives;

    private PhraseMatcher(
            Set<String> words, List<List<String>> phrases, List<PhraseMatcher> alternatives) {
        this.words = Set.copyOf(words);
        this.phrases = List.copyOf(phrases);
        this.alternatives = List.copyOf(alternatives);
    }

    /**
     * Builds a matcher from entries already in the normalised single-space form.
     *
     * @param normalizedPhrases output of {@link TextMatchNormalizer#normalizePhrase}; null and
     *     blank entries are skipped
     * @return a matcher that treats one-token entries as whole words and the rest as phrases
     */
    public static PhraseMatcher compile(Collection<String> normalizedPhrases) {
        Set<String> words = new HashSet<>();
        List<List<String>> phrases = new ArrayList<>();
        for (String normalizedPhrase : normalizedPhrases) {
            if (normalizedPhrase == null || normalizedPhrase.isBlank()) {
                continue;
            }
            List<String> tokens = Arrays.asList(normalizedPhrase.split(" "));
            if (tokens.size() == 1) {
                words.add(tokens.getFirst());
            } else {
                phrases.add(List.copyOf(tokens));
            }
        }
        return new PhraseMatcher(words, phrases, List.of());
    }

    /**
     * Returns a matcher that matches whatever this one or {@code other} matches.
     *
     * <p>It delegates instead of copying either side's entries, so combining a small personal list
     * with a large shared one costs nothing proportional to the shared list.
     */
    public PhraseMatcher or(PhraseMatcher other) {
        Objects.requireNonNull(other, "other");
        return new PhraseMatcher(Set.of(), List.of(), List.of(this, other));
    }

    /**
     * Reports whether any entry appears in the content.
     *
     * @param contentTokens output of {@link TextMatchNormalizer#tokens}; null matches nothing
     */
    public boolean matches(List<String> contentTokens) {
        if (contentTokens == null || contentTokens.isEmpty()) {
            return false;
        }
        for (PhraseMatcher alternative : alternatives) {
            if (alternative.matches(contentTokens)) {
                return true;
            }
        }
        for (String token : contentTokens) {
            if (words.contains(token)) {
                return true;
            }
        }
        for (List<String> phrase : phrases) {
            for (int start = 0; start <= contentTokens.size() - phrase.size(); start++) {
                if (contentTokens.subList(start, start + phrase.size()).equals(phrase)) {
                    return true;
                }
            }
        }
        return false;
    }
}
