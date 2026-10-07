package com.app.modules.message.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.app.common.security.config.CorsProperties;

class MessageWebSocketConfigTest {

    private static MessageWebSocketConfig configWith(String origins) {
        return new MessageWebSocketConfig(null, null, new CorsProperties(origins));
    }

    @Test
    void allowedOrigins_nullProperty_returnsLocalhostDefault() {
        assertThat(configWith(null).allowedOrigins()).containsExactly("http://localhost:*");
    }

    @Test
    void allowedOrigins_blankProperty_returnsLocalhostDefault() {
        assertThat(configWith("   ").allowedOrigins()).containsExactly("http://localhost:*");
    }

    @Test
    void allowedOrigins_spacesAroundCommas_returnsTrimmedOrigins() {
        assertThat(configWith("https://example.com , https://app.example.com").allowedOrigins())
                .containsExactly("https://example.com", "https://app.example.com");
    }

    @Test
    void allowedOrigins_surroundingWhitespace_returnsTrimmedOrigins() {
        assertThat(configWith("  https://example.com, https://app.example.com  ").allowedOrigins())
                .containsExactly("https://example.com", "https://app.example.com");
    }

    @Test
    void allowedOrigins_trailingComma_dropsEmptyEntry() {
        assertThat(configWith("https://example.com,https://app.example.com,").allowedOrigins())
                .containsExactly("https://example.com", "https://app.example.com");
    }

    @Test
    void allowedOrigins_emptyEntryBetweenCommas_dropsEmptyEntry() {
        assertThat(configWith("https://example.com,,https://app.example.com").allowedOrigins())
                .containsExactly("https://example.com", "https://app.example.com");
    }

    @Test
    void allowedOrigins_leadingComma_dropsEmptyEntry() {
        assertThat(configWith(",https://example.com").allowedOrigins())
                .containsExactly("https://example.com");
    }
}
