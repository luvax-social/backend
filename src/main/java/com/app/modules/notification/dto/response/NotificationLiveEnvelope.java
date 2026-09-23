package com.app.modules.notification.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What {@code /topic/notifications.{userId}} carries: one typed event about the recipient's feed,
 * and the feed state after it.
 *
 * <p>{@code event} is one of:
 *
 * <ul>
 *   <li>{@code upserted}: {@code item} was written, gained or lost an actor, or was converted from
 *       a follow request; place it by {@code (activityAt, id)} and drop any older copy;
 *   <li>{@code read-state}: the rows in {@code ids}, or every row at or below {@code upTo}, became
 *       read at {@code readAt}, or unread when {@code readAt} is absent;
 *   <li>{@code deleted}: the rows in {@code ids} left the feed;
 *   <li>{@code seen}: the watermark moved, from this or another tab;
 *   <li>{@code requests}: a pending follow request arrived; it is never a feed row, so only {@code
 *       state} (the badge and the pinned entry) changes.
 * </ul>
 *
 * <p>Every event carries {@code state}, so the badge is replaced from the envelope, never adjusted
 * client-side. Delivery is best effort; after a reconnect the client refetches, because a missed
 * event is not replayed.
 */
@Schema(description = "Typed live event on /topic/notifications.{userId}")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationLiveEnvelope(
        @Schema(
                        description = "upserted, read-state, deleted, seen or requests",
                        requiredMode = REQUIRED)
                String event,
        @Schema(description = "The row, for upserted", nullable = true)
                NotificationItemResponse item,
        @Schema(description = "Affected row ids, for read-state and deleted", nullable = true)
                List<UUID> ids,
        @Schema(description = "Bound of a mark-all-read, for read-state", nullable = true)
                NotificationKeyResponse upTo,
        @Schema(
                        description = "Read time for read-state; absent means marked unread",
                        nullable = true)
                OffsetDateTime readAt,
        @Schema(description = "Feed state after the event", requiredMode = REQUIRED)
                NotificationStateResponse state) {

    public static final String UPSERTED = "upserted";
    public static final String READ_STATE = "read-state";
    public static final String DELETED = "deleted";
    public static final String SEEN = "seen";
    public static final String REQUESTS = "requests";
}
