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

    /**
     * Emitted by {@code AdminActionRecorder} in the transaction that inserts an audit row, telling
     * the ClickHouse replica to fetch it.
     *
     * <p>Carries only the row's identifier: the consumer reads the current row from PostgreSQL, so
     * a copy delivered late, twice or after a newer change can never write stale content.
     */
    public static final String ACTION_RECORDED_V1 = "admin.action.recorded.v1";

    /**
     * Written by the {@code admin_actions} update trigger when PostgreSQL itself rewrites an audit
     * row, which happens when a cascade sets {@code admin_id}, {@code target_user_id} or {@code
     * report_id} to null.
     */
    public static final String ACTION_CHANGED_V1 = "admin.action.changed.v1";

    /**
     * Emitted once per collected statistics bucket, in the transaction that read the source tables,
     * carrying every metric row of the bucket for the ClickHouse {@code platform_stats} table.
     *
     * <p>A re-collection of the same bucket emits it again with a later {@code computedAt}, which
     * replaces the earlier rows when ClickHouse merges.
     */
    public static final String PLATFORM_STATS_COLLECTED_V1 = "admin.platform-stats.collected.v1";

    private AdminEventTypes() {}
}
