package com.app.common.outbox.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Binds scheduled outbox publisher settings from {@code app.outbox.publisher.*}. */
@ConfigurationProperties(prefix = "app.outbox.publisher")
@Getter
@Setter
public class OutboxPublisherProperties {

    private boolean enabled = true;
    private int batchSize = 100;
    private int maxAttempts = 3;
    private Duration confirmTimeout = Duration.ofSeconds(10);
    private Duration processingTimeout = Duration.ofMinutes(2);
    private Duration initialDelay = Duration.ofSeconds(10);
    private Duration fixedDelay = Duration.ofSeconds(1);
    private List<Duration> retryBackoffs =
            List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));

    public Duration retryBackoffForAttempt(int attemptCount) {
        if (retryBackoffs == null || retryBackoffs.isEmpty()) {
            return Duration.ZERO;
        }
        int index = Math.clamp(attemptCount - 1, 0, retryBackoffs.size() - 1);
        Duration backoff = retryBackoffs.get(index);
        if (backoff == null || backoff.isNegative()) {
            return Duration.ZERO;
        }
        return backoff;
    }

    public Duration resolvedProcessingTimeout() {
        if (processingTimeout == null
                || processingTimeout.isNegative()
                || processingTimeout.isZero()) {
            return Duration.ofMinutes(2);
        }
        return processingTimeout;
    }
}
