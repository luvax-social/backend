package com.app.common.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.app.common.retention.service.RetentionPurgeService;

/** Runs the retention purge on a fixed schedule; keeps its own root trace, one per run. */
@Component
@ConditionalOnProperty(
        prefix = "app.retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RetentionPurgeJob {

    private final RetentionPurgeService retentionPurgeService;

    public RetentionPurgeJob(RetentionPurgeService retentionPurgeService) {
        this.retentionPurgeService = retentionPurgeService;
    }

    @Scheduled(
            initialDelayString = "${app.retention.initial-delay:PT10M}",
            fixedDelayString = "${app.retention.fixed-delay:PT1H}")
    public void run() {
        retentionPurgeService.purge();
    }
}
