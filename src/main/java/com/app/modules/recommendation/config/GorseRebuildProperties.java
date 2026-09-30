package com.app.modules.recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the operator-triggered Gorse rebuild's pacing from {@code
 * app.recommendation.gorse-rebuild.*}.
 *
 * <p>The rebuild pushes a whole dataset into Gorse in one sitting, so every request is rate limited
 * and every batch bounded; none of these values changes what the rebuild produces.
 *
 * @param requestsPerSecond Gorse calls allowed per second across the whole run
 * @param userBatchSize users pushed per request, and accounts whose feedback is read per batch
 * @param itemBatchSize posts pushed per request
 * @param feedbackBatchSize feedback tuples sent per request
 */
@ConfigurationProperties(prefix = "app.recommendation.gorse-rebuild")
public record GorseRebuildProperties(
        @DefaultValue("5") int requestsPerSecond,
        @DefaultValue("500") int userBatchSize,
        @DefaultValue("500") int itemBatchSize,
        @DefaultValue("1000") int feedbackBatchSize) {}
