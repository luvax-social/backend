package com.app.common.inbox.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.app.common.inbox.entity.ProcessedMessage;

public interface ProcessedMessageRepositoryCustom {

    Optional<ProcessedMessage> insertIfAbsent(String consumerName, UUID eventId, String eventType);

    /**
     * Deletes rows processed before {@code cutoff}, oldest first, up to {@code limit} rows.
     *
     * @param cutoff the exclusive age boundary; a row's {@code processed_at} must be before it
     * @param limit the maximum number of rows one call may delete
     * @return how many rows were deleted
     */
    int deleteProcessedBefore(OffsetDateTime cutoff, int limit);
}
