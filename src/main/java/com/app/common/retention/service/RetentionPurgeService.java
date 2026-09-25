package com.app.common.retention.service;

/** Purges infrastructure rows that have outlived their retention window. */
public interface RetentionPurgeService {

    /**
     * Purges PUBLISHED outbox rows and processed-message markers older than their configured
     * windows, one bounded batch per table per call, until a batch is short or the per-run batch
     * cap is reached.
     */
    void purge();
}
