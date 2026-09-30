package com.app.modules.recommendation.messaging;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.app.common.messaging.exception.PermanentMessageException;
import com.app.modules.recommendation.enums.UserEventType;

/**
 * The payload contract of {@link RecommendationEventTypes#USER_EVENT_IMPORTED_V1}: one behavioural
 * event to be stored as it is.
 *
 * <p>The producer and the consumer both go through this class, so the two ends of the queue cannot
 * drift apart on a key name. The account is the event's aggregate identifier, and the event's own
 * identifier is the row identifier.
 */
public final class UserEventImportedEvent {

    public static final String AGGREGATE_TYPE = "user";

    private UserEventImportedEvent() {}

    /** The event an import carries, as the consumer reads it. */
    public record Imported(
            UserEventType eventType,
            String entityType,
            UUID entityId,
            String metadata,
            Instant createdAt) {}

    /**
     * Builds the event's {@code data} map. Absent fields are omitted, because the outbox payload
     * cannot hold a null value.
     *
     * @param eventType what happened
     * @param entityType kind of thing acted on, null when none
     * @param entityId thing acted on, null when none
     * @param metadata JSON text, null when none
     * @param createdAt when the event happened
     */
    public static Map<String, Object> payload(
            UserEventType eventType,
            String entityType,
            UUID entityId,
            String metadata,
            Instant createdAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventType", eventType.toJson());
        if (entityType != null) {
            data.put("entityType", entityType);
        }
        if (entityId != null) {
            data.put("entityId", entityId.toString());
        }
        if (metadata != null && !metadata.isEmpty()) {
            data.put("metadata", metadata);
        }
        data.put("createdAt", createdAt.toString());
        return data;
    }

    /**
     * Reads a delivered event's {@code data} map.
     *
     * @throws PermanentMessageException when the payload is missing a field or holds a value of the
     *     wrong shape, because redelivering it cannot make it valid
     */
    public static Imported parse(Map<String, Object> data) {
        if (data == null) {
            throw new PermanentMessageException("User event import has no data");
        }
        try {
            UserEventType eventType = UserEventType.fromJson(text(data, "eventType"));
            Object rawEntityId = data.get("entityId");
            Object rawMetadata = data.get("metadata");
            Object rawEntityType = data.get("entityType");
            return new Imported(
                    eventType,
                    rawEntityType == null ? null : rawEntityType.toString(),
                    rawEntityId == null ? null : UUID.fromString(rawEntityId.toString()),
                    rawMetadata == null ? "" : rawMetadata.toString(),
                    Instant.parse(text(data, "createdAt")));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
            throw new PermanentMessageException("User event import has a malformed field", ex);
        }
    }

    private static String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new PermanentMessageException("User event import has no " + key);
        }
        return value.toString();
    }
}
