package com.app.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PhraseMatcherTest {
    @Test
    void matches_singleWord_requiresWholeToken() {
        PhraseMatcher matcher =
                PhraseMatcher.compile(List.of(TextMatchNormalizer.normalizePhrase("ass")));

        assertThat(matcher.matches(TextMatchNormalizer.tokens("a classic class"))).isFalse();
        assertThat(matcher.matches(TextMatchNormalizer.tokens("an ass"))).isTrue();
    }

    @Test
    void matches_mentionMarker_andLeetspeakDiffer() {
        PhraseMatcher matcher =
                PhraseMatcher.compile(List.of(TextMatchNormalizer.normalizePhrase("ass")));

        assertThat(matcher.matches(TextMatchNormalizer.tokens("what an @ss"))).isFalse();
        assertThat(matcher.matches(TextMatchNormalizer.tokens("what an a$$"))).isTrue();
    }

    @Test
    void matches_phrase_requiresContiguousTokens() {
        PhraseMatcher matcher =
                PhraseMatcher.compile(List.of(TextMatchNormalizer.normalizePhrase("đồ ngu")));

        assertThat(matcher.matches(TextMatchNormalizer.tokens("mày là ĐỒ NGU"))).isTrue();
        assertThat(matcher.matches(TextMatchNormalizer.tokens("đồ rất ngu"))).isFalse();
    }

    @Test
    void matches_emptyPhraseList_matchesNothing() {
        PhraseMatcher matcher = PhraseMatcher.compile(List.of());

        assertThat(matcher.matches(TextMatchNormalizer.tokens("anything"))).isFalse();
        assertThat(matcher.matches(List.of())).isFalse();
    }
}
