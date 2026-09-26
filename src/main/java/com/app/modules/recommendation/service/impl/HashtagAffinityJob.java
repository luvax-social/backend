package com.app.modules.recommendation.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.app.modules.recommendation.service.HashtagAffinityService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives the hashtag affinity recompute on a fixed cycle.
 *
 * <p>A single application instance is assumed and there is no distributed scheduler lock, exactly
 * as {@code StatsCollectionJob} assumes for {@code platform_stats}. What makes that safe here is
 * the write shape rather than the schedule: the recompute is an upsert against {@code (user_id,
 * hashtag_id)}, so a second concurrent run rewrites the same rows with the same values instead of
 * duplicating them.
 *
 * <p>Failures are caught and logged rather than propagated. Spring's scheduler abandons a {@code
 * fixedDelay} task whose method throws, which would silently stop every later run; this model is
 * rebuildable and a missed cycle is corrected by the next one, so surviving to the next cycle
 * matters more than surfacing the failure here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.recommendation.affinity.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HashtagAffinityJob {

    private final HashtagAffinityService hashtagAffinityService;

    /** Recomputes every user's hashtag affinity on the configured cycle. */
    @Scheduled(
            initialDelayString = "${app.recommendation.affinity.initial-delay:PT2M}",
            fixedDelayString = "${app.recommendation.affinity.interval:PT12H}")
    public void run() {
        try {
            hashtagAffinityService.recompute();
        } catch (RuntimeException ex) {
            log.error("[affinity] recompute failed; the next cycle will retry", ex);
        }
    }
}
