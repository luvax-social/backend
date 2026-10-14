package com.app.modules.message.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ReactionEmojiValidatorTest {

    // Escapes keep each code point visible: thumbs up; red heart with the emoji selector; a
    // man-woman-girl-boy family joined by zero-width joiners; thumbs up with a medium skin tone;
    // the Vietnam flag; keycap one; the rainbow flag.
    @ParameterizedTest
    @ValueSource(strings = {"👍", "❤️", "👨‍👩‍👧‍👦", "👍🏽", "🇻🇳", "1️⃣", "🏳️‍🌈"})
    void accepts_singleEmojiGrapheme(String s) {
        assertThat(ReactionEmojiValidator.isSingleEmoji(s)).isTrue();
    }

    // Empty; a letter; a bare digit; two thumbs up; a thumbs up and a space; two letters; a red
    // heart without the emoji selector; a lone zero-width joiner.
    @ParameterizedTest
    @ValueSource(strings = {"", "a", "1", "👍👍", "👍 ", "ok", "❤", "‍"})
    void rejects_nonEmojiOrMultiple(String s) {
        assertThat(ReactionEmojiValidator.isSingleEmoji(s)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejects_nullAndEmpty(String s) {
        assertThat(ReactionEmojiValidator.isSingleEmoji(s)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"#️⃣", "*️⃣", "0️⃣", "9⃣"})
    void accepts_everyKeycapBase(String s) {
        assertThat(ReactionEmojiValidator.isSingleEmoji(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a⃣", "a️⃣", "👍⃣x", "1️"})
    void rejects_keycapMarkOnANonKeycapBaseAndBareVariationSelector(String s) {
        assertThat(ReactionEmojiValidator.isSingleEmoji(s)).isFalse();
    }

    @Test
    void rejects_loneRegionalIndicatorAndTextFlagLookalikes() {
        assertThat(ReactionEmojiValidator.isSingleEmoji("🇻")).isFalse();
        assertThat(ReactionEmojiValidator.isSingleEmoji("🇻🇳🇻")).isFalse();
        assertThat(ReactionEmojiValidator.isSingleEmoji("VN")).isFalse();
    }

    @Test
    void rejects_textPresentationSymbolsUnlessMarkedAsEmoji() {
        assertThat(ReactionEmojiValidator.isSingleEmoji("©")).isFalse();
        assertThat(ReactionEmojiValidator.isSingleEmoji("©️")).isTrue();
        assertThat(ReactionEmojiValidator.isSingleEmoji("☃")).isFalse();
        assertThat(ReactionEmojiValidator.isSingleEmoji("☃️")).isTrue();
    }

    @Test
    void rejects_graphemeLongerThanTheColumn() {
        String joined = "👨" + "‍👩".repeat(12);

        assertThat(joined.length()).isGreaterThan(32);
        assertThat(ReactionEmojiValidator.isSingleEmoji(joined)).isFalse();
    }

    @Test
    void rejects_emojiAdjacentToTextOrLineBreak() {
        assertThat(ReactionEmojiValidator.isSingleEmoji("👍a")).isFalse();
        assertThat(ReactionEmojiValidator.isSingleEmoji("a👍")).isFalse();
        assertThat(ReactionEmojiValidator.isSingleEmoji("👍\n")).isFalse();
    }
}
