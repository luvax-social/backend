package com.app.modules.recommendation.service.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.app.modules.recommendation.repository.UserSuggestionRepository;
import com.app.modules.recommendation.service.SuggestionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rebuilds every account's people-you-may-know list on a twelve-hour cycle.
 *
 * <p>A single application instance is assumed and there is no distributed scheduler lock, exactly
 * as {@code HashtagAffinityJob} and {@code StatsCollectionJob} assume. What makes that safe is the
 * write shape rather than the schedule: every row is written with {@code ON CONFLICT DO UPDATE} on
 * {@code (user_id, suggested_id)}, so a second concurrent run rewrites the same rows with the same
 * values instead of duplicating them.
 *
 * <p>The twelve-hour cycle is also why every exclusion rule is re-applied at read time rather than
 * only here. A viewer who follows somebody at ten in the morning would otherwise keep being offered
 * them until the evening.
 *
 * <p>One account's failure is logged and the sweep continues. A single unbuildable list must not
 * cost every later account its rebuild, and the model is rebuildable, so the next cycle corrects
 * whatever this one missed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.recommendation.suggestions.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SuggestionPrecomputeJob {

    private final SuggestionService suggestionService;
    private final UserSuggestionRepository userSuggestionRepository;

    /** Rebuilds the suggestion read model for every live active account. */
    @Scheduled(
            initialDelayString = "${app.recommendation.suggestions.initial-delay:PT3M}",
            fixedDelayString = "${app.recommendation.suggestions.interval:PT12H}")
    public void run() {
        long started = System.currentTimeMillis();
        List<UUID> viewers;
        try {
            viewers = userSuggestionRepository.findViewersToCompute();
        } catch (RuntimeException ex) {
            log.error("[suggestions] could not read the viewer set; the next cycle will retry", ex);
            return;
        }

        int rebuilt = 0;
        int failed = 0;
        int rows = 0;
        for (UUID viewer : viewers) {
            try {
                rows += suggestionService.rebuildFor(viewer);
                rebuilt++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("[suggestions] rebuild failed for one account | viewer: {}", viewer, ex);
            }
        }
        log.info(
                "[suggestions] cycle complete | viewers: {} | rebuilt: {} | failed: {} | rows: {} |"
                        + " tookMs: {}",
                viewers.size(),
                rebuilt,
                failed,
                rows,
                System.currentTimeMillis() - started);
    }
}
