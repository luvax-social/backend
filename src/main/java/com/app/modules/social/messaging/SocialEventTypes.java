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

    private SocialEventTypes() {}
}
