package com.app.modules.recommendation.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.app.modules.recommendation.enums.UserEventType;

/**
 * One behavioural event as the activity log reads it from ClickHouse.
 *
 * <p>A plain read model rather than an entity: {@code user_events} is no longer a mapped table, and
 * the row is only ever presented, never modified.
 *
 * @param id event identifier
 * @param userId account the event is attributed to
 * @param eventType what happened
 * @param entityType kind of thing acted on, null when the event names no target
 * @param entityId thing acted on, null when the event names no target
 * @param metadata JSON text stored with the event, empty when there is none
 * @param createdAt when the event happened, in UTC
 */
public record UserEventRow(
        UUID id,
        UUID userId,
        UserEventType eventType,
        String entityType,
        UUID entityId,
        String metadata,
        OffsetDateTime createdAt) {}
