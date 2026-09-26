package com.app.common.outbox.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.app.common.outbox.entity.OutboxEvent;

public interface OutboxEventRepositoryCustom {

    OutboxEvent insertPending(OutboxEvent event);

    /**
     * Inserts a pending event, ignoring a row whose {@code event_id} is already present.
     *
     * @param event the event to enqueue, carrying a caller-supplied event id
     * @return the inserted event, or empty when that event id was already enqueued
     */
    Optional<OutboxEvent> insertPendingIgnoreDuplicate(OutboxEvent event);

    List<OutboxEvent> claimPublishableBatch(
            OffsetDateTime now,
            OffsetDateTime claimedAt,
            OffsetDateTime claimedUntil,
            int batchSize);

    boolean markPublished(UUID id, UUID eventId, UUID claimId, OffsetDateTime publishedAt);

    boolean markFailed(
            UUID id,
            UUID eventId,
            UUID claimId,
            int attemptCount,
            OffsetDateTime nextRetryAt,
            String lastError);

    boolean markDead(
            UUID id,
            UUID eventId,
            UUID claimId,
            int attemptCount,
            OffsetDateTime deadAt,
            String lastError);

    /**
     * Deletes PUBLISHED rows older than {@code cutoff}, oldest first, up to {@code limit} rows.
     *
     * @param cutoff the exclusive age boundary; a row's {@code published_at} must be before it
     * @param limit the maximum number of rows one call may delete
     * @return how many rows were deleted
     */
    int deletePublishedBefore(OffsetDateTime cutoff, int limit);
}
