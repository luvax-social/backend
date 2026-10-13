package com.app.modules.comment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Comment module settings bound from the {@code app.comment} namespace.
 *
 * <p>{@code slowModeSeconds} of 0 disables slow mode. The word list that used to live here moved to
 * the administrator-managed Hidden Words registry; {@code LegacyBlockedWordsGuard} refuses to start
 * while the old property is still configured.
 */
@ConfigurationProperties(prefix = "app.comment")
public record CommentProperties(int slowModeSeconds, int idempotencyTtlHours) {

    public CommentProperties {
        idempotencyTtlHours = idempotencyTtlHours <= 0 ? 24 : idempotencyTtlHours;
    }
}
