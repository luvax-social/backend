package com.app.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.json.JsonMapper;

class TextMatchNormalizerTest {
    record Vector(String raw, List<String> tokens) {}

    static Stream<Vector> vectors() throws IOException {
        try (InputStream in =
                TextMatchNormalizerTest.class.getResourceAsStream(
                        "/text-match/text-match-vectors.json")) {
            return Stream.of(JsonMapper.builder().build().readValue(in, Vector[].class));
        }
    }

    @ParameterizedTest
    @MethodSource("vectors")
    void tokens_vector_matchesContract(Vector vector) {
        assertThat(TextMatchNormalizer.tokens(vector.raw()))
                .containsExactlyElementsOf(vector.tokens());
    }

    @Test
    void tokens_nullInput_returnsEmptyList() {
        assertThat(TextMatchNormalizer.tokens(null)).isEmpty();
    }

    @Test
    void normalizePhrase_multipleTokens_joinsWithSingleSpaces() {
        assertThat(TextMatchNormalizer.normalizePhrase("  ĐỒ, ngu!!!  ")).isEqualTo("do ngu");
    }
}
