package com.app.common.messaging.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Shared bounded retry settings for RabbitMQ consumers. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.messaging.consumer")
public class ConsumerRetryProperties {

    private int maxAttempts = 3;

    private List<Duration> retryBackoffs =
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));

    public int resolvedMaxAttempts() {
        return Math.max(1, maxAttempts);
    }

    public Duration retryBackoffForAttempt(int attempt) {
        if (retryBackoffs == null || retryBackoffs.isEmpty()) {
            return Duration.ZERO;
        }
        int index = Math.clamp((long) attempt - 1, 0, retryBackoffs.size() - 1);
        Duration backoff = retryBackoffs.get(index);
        if (backoff == null || backoff.isNegative()) {
            return Duration.ZERO;
        }
        return backoff;
    }
}
