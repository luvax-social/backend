package com.app.modules.message.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

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

    private static final int THUMBS_UP = 0x1F44D;
    private static final int ZWJ = 0x200D;
    private static final int VS16 = 0xFE0F;

    // Each of these is a single grapheme cluster that is still not one emoji: trailing tag
    // characters, stacked combining marks, a spacing mark, a dangling joiner, repeated or text
    // variation selectors, a prepended mark, a long joiner chain and a keycap on a non-keycap base.
    @Test
    void rejects_singleClusterThatIsNotOneEmoji() {
        List<int[]> shapes =
                List.of(
                        new int[] {THUMBS_UP, 0xE0048, 0xE0049, 0xE004A, 0xE004B, 0xE004C, 0xE004D},
                        new int[] {THUMBS_UP, 0xE0048, 0xE004F},
                        new int[] {THUMBS_UP, 0x0301, 0x0302, 0x0303, 0x0304, 0x0305},
                        new int[] {THUMBS_UP, 0x0903},
                        new int[] {THUMBS_UP, ZWJ},
                        new int[] {THUMBS_UP, VS16, VS16, VS16, VS16},
                        new int[] {THUMBS_UP, 0xFE0E},
                        new int[] {0x0600, THUMBS_UP, VS16},
                        new int[] {
                            THUMBS_UP, ZWJ, THUMBS_UP, ZWJ, THUMBS_UP, ZWJ, THUMBS_UP, ZWJ,
                            THUMBS_UP, ZWJ, THUMBS_UP, ZWJ, THUMBS_UP
                        },
                        new int[] {THUMBS_UP, 0x20E3},
                        new int[] {0x1F3FD});
        for (int[] shape : shapes) {
            String text = new String(shape, 0, shape.length);

            assertThat(ReactionEmojiValidator.isSingleEmoji(text))
                    .as("code points %s", Arrays.toString(shape))
                    .isFalse();
        }
    }

    @Test
    void accepts_everyShapeThePickerEmits() {
        List<int[]> shapes =
                List.of(
                        // couple with heart, pirate flag, health worker, handshake with two tones
                        new int[] {0x1F469, ZWJ, 0x2764, VS16, ZWJ, 0x1F468},
                        new int[] {0x1F3F4, ZWJ, 0x2620, VS16},
                        new int[] {0x1F9D1, ZWJ, 0x2695, VS16},
                        new int[] {0x1F9D1, 0x1F3FD, ZWJ, 0x1F91D, ZWJ, 0x1F9D1, 0x1F3FB},
                        // text-default base carried by a skin tone, then the England subdivision
                        // flag
                        new int[] {0x261D, 0x1F3FD},
                        new int[] {0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F},
                        new int[] {THUMBS_UP, ZWJ, THUMBS_UP, ZWJ, THUMBS_UP, ZWJ, THUMBS_UP});
        for (int[] shape : shapes) {
            String text = new String(shape, 0, shape.length);

            assertThat(ReactionEmojiValidator.isSingleEmoji(text))
                    .as("code points %s", Arrays.toString(shape))
                    .isTrue();
        }
    }

    @Test
    void rejects_tagSequenceOnABaseOtherThanTheBlackFlag() {
        int[] shape = {THUMBS_UP, 0xE0067, 0xE0062, 0xE007F};

        assertThat(ReactionEmojiValidator.isSingleEmoji(new String(shape, 0, shape.length)))
                .isFalse();
    }
}
