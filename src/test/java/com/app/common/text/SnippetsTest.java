package com.app.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SnippetsTest {

    @Test
    void of_blankOrNull_isNull() {
        assertThat(Snippets.of(null)).isNull();
        assertThat(Snippets.of("  \n\t ")).isNull();
    }

    @Test
    void of_shortText_isFlattenedToOneLine() {
        assertThat(Snippets.of("  love\n\nthis   light  ")).isEqualTo("love this light");
    }

    @Test
    void of_exactlyTheLimit_isUntouched() {
        String text = "a".repeat(Snippets.MAX_CODE_POINTS);

        assertThat(Snippets.of(text)).isEqualTo(text);
    }

    @Test
    void of_longText_isCutToTheLimitWithAnEllipsis() {
        String snippet = Snippets.of("b".repeat(500));

        assertThat(snippet.codePointCount(0, snippet.length())).isEqualTo(Snippets.MAX_CODE_POINTS);
        assertThat(snippet).endsWith("…");
    }

    @Test
    void of_textOutsideTheBasicPlane_isNeverCutInsideACharacter() {
        String emoji = "😀";
        String snippet = Snippets.of(emoji.repeat(300));

        assertThat(snippet.codePointCount(0, snippet.length())).isEqualTo(Snippets.MAX_CODE_POINTS);
        assertThat(snippet.substring(0, snippet.length() - 1)).matches("(" + emoji + ")+");
    }
}
