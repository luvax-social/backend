package com.app.modules.notification.messaging;

/**
 * Versioned notification events published through the transactional outbox.
 *
 * <p>Every one is routed by the {@code notification.#} exchange-to-exchange binding into {@code
 * notification.live.events}, where the live tier turns it into a typed envelope for the recipient's
 * socket. Each carries {@code recipientId} in its data; the aggregate id is the notification it
 * concerns, or the recipient for events about the feed as a whole.
 */
public final class NotificationEventTypes {

    /** A row was written, gained an actor, lost an actor but not its last, or was converted. */
    public static final String NOTIFICATION_UPSERTED_V1 = "notification.upserted.v1";

    /** Rows were marked read or unread. Data: {@code ids} or {@code upTo}, and {@code readAt}. */
    public static final String NOTIFICATION_READ_STATE_CHANGED_V1 =
            "notification.read-state-changed.v1";

    /** Rows left the feed: deleted by the recipient, emptied by retraction. Data: {@code ids}. */
    public static final String NOTIFICATION_DELETED_V1 = "notification.deleted.v1";

    /** The recipient's seen watermark advanced. */
    public static final String NOTIFICATION_SEEN_V1 = "notification.seen.v1";

    private NotificationEventTypes() {}
}
