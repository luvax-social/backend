package com.app.common.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Matches normalised Hidden Words entries against contiguous content tokens. */
public final class PhraseMatcher {
    private final Set<String> words;
    private final List<List<String>> phrases;

    private PhraseMatcher(Set<String> words, List<List<String>> phrases) {
        this.words = Set.copyOf(words);
        this.phrases = List.copyOf(phrases);
    }

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
        return new PhraseMatcher(words, phrases);
    }

    public boolean matches(List<String> contentTokens) {
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
