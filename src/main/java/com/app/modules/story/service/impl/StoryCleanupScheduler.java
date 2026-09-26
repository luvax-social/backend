package com.app.modules.story.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.app.modules.story.repository.StoryRepository;

/**
 * Periodically hard-deletes {@code stories} rows that are BOTH soft-deleted AND expired (DATA_RULES
 * §3B).
 *
 * <p>Expired-but-not-yet-deleted stories are never targets: they stay readable-as-404 via the
 * expiry filter and remain deletable by their owner until this job's window catches them. Cascading
 * {@code story_views} rows are removed by the database via {@code ON DELETE CASCADE}.
 */
@Component
public class StoryCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(StoryCleanupScheduler.class);

    private final StoryRepository storyRepository;

    public StoryCleanupScheduler(StoryRepository storyRepository) {
        this.storyRepository = storyRepository;
    }

    @Scheduled(
            initialDelayString = "${app.story.cleanup.initial-delay:PT10M}",
            fixedDelayString = "${app.story.cleanup.fixed-delay:PT1H}")
    @Transactional
    public void purgeSoftDeletedExpiredStories() {
        // One clock domain: expires_at is written from the JVM, so the cutoff comes from the JVM
        // too rather than from the database's own NOW().
        int purged =
                storyRepository.purgeSoftDeletedExpired(
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        if (purged > 0) {
            log.info("StoryCleanupScheduler: hard-deleted {} soft-deleted expired stories", purged);
        }
    }
}
