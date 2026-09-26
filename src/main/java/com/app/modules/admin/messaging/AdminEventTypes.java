package com.app.modules.admin.messaging;

/** Versioned admin domain event types published through the transactional outbox. */
public final class AdminEventTypes {

    /**
     * Emitted when a warning is issued, so the warned account is told.
     *
     * <p>Named for what happened to the account rather than for what a moderator did, because the
     * consumer's only job is to notify the account, and the moderator is deliberately not named in
     * what it delivers.
     */
    public static final String USER_WARNED_V1 = "user.warned.v1";

    /**
     * Emitted when a moderation decision has been taken that its subject must be told about by
     * mail.
     *
     * <p>Deliberately separate from {@link #USER_WARNED_V1} rather than reusing it. A warning is
     * the one action that already had an event, and binding the moderation mail queue to that key
     * would have tied the two concerns together: the notification and the mail would then share one
     * payload, one binding and one failure mode, and neither could be turned off without the other.
     * They are independent decisions - a deployment may want in-app notifications without outbound
     * mail, or the reverse - so they travel as separate events on separate queues with separate
     * enablement flags. The cost is one extra outbox row when an account is warned, which is the
     * cheaper half of the trade.
     *
     * <p>Named {@code requested} to match the auth module's mail-bearing events rather than the
     * past-tense domain events, because what it carries is a request to send, not a new fact about
     * the account beyond the action already recorded in {@code admin_actions}.
     */
    public static final String MODERATION_NOTICE_REQUESTED_V1 =
            "admin.moderation-notice.requested.v1";

    private AdminEventTypes() {}
}
