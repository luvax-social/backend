package com.app.modules.recommendation.messaging;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.recommendation.enums.UserEventType;

/**
 * One {@code user_events} row read from a mapped domain event, ready to be written.
 *
 * @param userId the account the row is attributed to
 * @param eventType what happened
 * @param entityType kind of thing acted on
 * @param entityId thing acted on
 * @param metadata JSON text built from fields the event carries, or empty
 * @param createdAt when the domain event happened, never the delivery time
 */
public record ResolvedSignal(
        UUID userId,
        UserEventType eventType,
        String entityType,
        UUID entityId,
        String metadata,
        OffsetDateTime createdAt) {}
