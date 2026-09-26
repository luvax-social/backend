package com.app.modules.recommendation.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the hashtag affinity job's window and decay settings from {@code
 * app.recommendation.affinity.*}.
 *
 * @param window how far back the recompute reads {@code user_events}; also the bound that makes the
 *     read prune partitions instead of scanning every one ever declared
 * @param halfLife age at which a contribution is worth half its original weight
 * @param topLimit maximum affinity rows a personalised surface reads for one user
 */
@ConfigurationProperties(prefix = "app.recommendation.affinity")
public record HashtagAffinityProperties(Duration window, Duration halfLife, int topLimit) {}
