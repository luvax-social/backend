package com.app.modules.notification.service;

import java.util.Objects;
import java.util.UUID;

import com.app.modules.notification.entity.enums.NotificationType;

/**
 * A notification a producer asks the notification module to write.
 *
 * @param actorId the user who acted; null for a platform notice (moderation, support)
 * @param recipientId the user who is told
 * @param type what happened
 * @param entityType the kind of target {@code entityId} names; null when the target is the
 *     recipient (follows)
 * @param entityId the target: the liked post or comment, the comment written, the story viewed, the
 *     user_warnings row, the report, the support ticket, or the audit row of a content removal
 * @param postId the post the target belongs to, so a client can open it without resolving the
 *     target first; null for non-content types
 * @param message the moderation reason shown with a platform notice; null otherwise
 * @param adminActionId the audit row behind a moderation notice, which carries the affected content
 *     and the appeal route; null for every other type
 */
public record NotificationDraft(
        UUID actorId,
        UUID recipientId,
        NotificationType type,
        String entityType,
        UUID entityId,
        UUID postId,
        String message,
        UUID adminActionId) {

    public NotificationDraft {
        Objects.requireNonNull(recipientId, "recipientId must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }

    /** A notification caused by a user, with no message and no audit row. */
    public static NotificationDraft of(
            UUID actorId,
            UUID recipientId,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId) {
        return new NotificationDraft(
                actorId, recipientId, type, entityType, entityId, postId, null, null);
    }

    /**
     * A platform notice: no actor, an optional reason, and the audit row it reports.
     *
     * @param adminActionId the audit row; null only for a notice with no moderation decision behind
     *     it (a support answer)
     */
    public static NotificationDraft systemNotice(
            UUID recipientId,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId,
            String message,
            UUID adminActionId) {
        return new NotificationDraft(
                null, recipientId, type, entityType, entityId, postId, message, adminActionId);
    }
}
