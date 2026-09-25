package com.app.common.retention.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.inbox.repository.ProcessedMessageRepository;
import com.app.common.outbox.repository.OutboxEventRepository;
import com.app.common.retention.RetentionProperties;
import com.app.common.retention.service.RetentionPurgeService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Deletes retention rows in bounded batches.
 *
 * <p>{@link #purge()} is not itself transactional: each batch is its own transaction, obtained
 * through the injected self-reference so the call crosses Spring's transactional proxy rather than
 * an internal method call that would bypass it. A batch that deletes fewer than {@code batch-size}
 * rows means the table is caught up, so the loop for that table stops there rather than spending
 * one more round trip to confirm it.
 */
@Service
public class RetentionPurgeServiceImpl implements RetentionPurgeService {

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedMessageRepository processedMessageRepository;
    private final RetentionProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObjectProvider<RetentionPurgeServiceImpl> self;

    public RetentionPurgeServiceImpl(
            OutboxEventRepository outboxEventRepository,
            ProcessedMessageRepository processedMessageRepository,
            RetentionProperties properties,
            MeterRegistry meterRegistry,
            ObjectProvider<RetentionPurgeServiceImpl> self) {
        this.outboxEventRepository = outboxEventRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.self = self;
    }

    @Override
    public void purge() {
        RetentionPurgeServiceImpl proxy = this.self.getObject();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        purgeTable(
                "outbox_events",
                now.minus(asDuration(properties.getOutboxPublished())),
                proxy::purgeOutboxBatch);
        purgeTable(
                "processed_messages",
                now.minus(asDuration(properties.getProcessedMessages())),
                proxy::purgeProcessedMessagesBatch);
    }

    private void purgeTable(String table, OffsetDateTime cutoff, BatchDeleter deleter) {
        int batchSize = Math.max(1, properties.getBatchSize());
        int maxBatches = Math.max(1, properties.getMaxBatchesPerRun());
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = deleter.deleteBatch(cutoff, batchSize);
            if (deleted > 0) {
                Counter.builder("luvax.retention.deleted")
                        .tag("table", table)
                        .register(meterRegistry)
                        .increment(deleted);
            }
            if (deleted < batchSize) {
                return;
            }
        }
    }

    @Transactional
    public int purgeOutboxBatch(OffsetDateTime cutoff, int limit) {
        return outboxEventRepository.deletePublishedBefore(cutoff, limit);
    }

    @Transactional
    public int purgeProcessedMessagesBatch(OffsetDateTime cutoff, int limit) {
        return processedMessageRepository.deleteProcessedBefore(cutoff, limit);
    }

    private static Duration asDuration(Period period) {
        return Duration.ofDays(
                period.getDays() + (period.getMonths() * 30L) + (period.getYears() * 365L));
    }

    @FunctionalInterface
    private interface BatchDeleter {
        int deleteBatch(OffsetDateTime cutoff, int limit);
    }
}
