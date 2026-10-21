package com.app.modules.social.messaging;

/** Versioned social-domain event types published through the transactional outbox. */
public final class SocialEventTypes {

    public static final String USER_FOLLOWED_V1 = "user.followed.v1";
    public static final String USER_FOLLOW_REQUESTED_V1 = "user.follow-requested.v1";

    /** A follow edge was removed by its follower; carries the status the edge had. */
    public static final String USER_UNFOLLOWED_V1 = "user.unfollowed.v1";

    public static final String USER_FOLLOW_REQUEST_APPROVED_V1 = "user.follow-request.approved.v1";
    public static final String USER_FOLLOW_REQUEST_REJECTED_V1 = "user.follow-request.rejected.v1";
    public static final String USER_BLOCKED_V1 = "user.blocked.v1";

    /**
     * An account turned its interaction limit on, replaced it, or turned it off.
     *
     * <p>The payload carries both toggles and, while a limit is in force, its end time; the end
     * time is absent once the limit is off.
     */
    public static final String USER_INTERACTION_LIMIT_CHANGED_V1 =
            "user.interaction-limit.changed.v1";

    /**
     * An account asked to see less of one author in recommendations; carries the author only.
     *
     * <p>Private to the account that made the request: it is never bound to a notification queue.
     */
    public static final String USER_SHOW_FEWER_V1 = "user.show-fewer.v1";

    /** An account took back its show-fewer request; carries the author only. */
    public static final String USER_SHOW_FEWER_UNDONE_V1 = "user.show-fewer-undone.v1";

    /**
     * An account muted another account's posts, stories or both, or changed which of the two.
     *
     * <p>The payload carries the muted account and both scope flags. The muted account is never
     * notified, so the event is never bound to a notification queue.
     */
    public static final String USER_MUTED_V1 = "user.muted.v1";

    /** An account removed its mute of another account; carries the formerly muted account only. */
    public static final String USER_UNMUTED_V1 = "user.unmuted.v1";

    private SocialEventTypes() {}
}
