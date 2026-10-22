package com.app.modules.post.messaging;

/**
 * Versioned post domain event types published through the transactional outbox.
 *
 * <p>Post routing keys are namespaced by the tier that consumes them, not by the domain verb alone.
 * {@code post.index.*} feeds the Elasticsearch sync queue; {@code post.live.*} feeds the realtime
 * fanout tier. The two namespaces are structurally disjoint so the live tier's exchange-to-exchange
 * binding cannot match an index-sync event, which a {@code post.#} binding would have done.
 */
public final class PostEventTypes {

    public static final String POST_INDEX_UPSERT_V1 = "post.index.upsert.v1";
    public static final String POST_INDEX_DELETE_V1 = "post.index.delete.v1";
    public static final String POST_LIKED_V1 = "post.liked.v1";

    /**
     * A like was withdrawn. Consumed by the notification tier to retract the liker from the
     * like-group; deliberately outside {@code post.index.*} and {@code post.live.*}.
     */
    public static final String POST_UNLIKED_V1 = "post.unliked.v1";

    public static final String POST_SAVED_V1 = "post.saved.v1";
    public static final String POST_VIEWED_V1 = "post.viewed.v1";

    /**
     * A viewer declared a post not interesting.
     *
     * <p>Carries the post and its author. Outside {@code post.index.*} and {@code post.live.*}, and
     * never bound to a notification queue: the author is not told.
     */
    public static final String POST_NOT_INTERESTED_V1 = "post.not-interested.v1";

    /** A viewer took back a not-interested mark; carries the post only. */
    public static final String POST_NOT_INTERESTED_UNDONE_V1 = "post.not-interested-undone.v1";

    /**
     * A post became published, by the owner or by the scheduled publisher.
     *
     * <p>Carries the post, its owner and how it was published. Bound to the behavioural signal
     * import only; the people the post involves are told by their own events below.
     */
    public static final String POST_PUBLISHED_V1 = "post.published.v1";

    /** The owner scheduled a post; carries the due time and how far ahead it is. */
    public static final String POST_SCHEDULED_V1 = "post.scheduled.v1";

    /**
     * A scheduled post could not be published and went back to drafts.
     *
     * <p>Carries a reason code. The notification tier tells the owner; the signal import records
     * it.
     */
    public static final String POST_SCHEDULE_FAILED_V1 = "post.schedule-failed.v1";

    /** The owner saved a draft; carries the post only. */
    public static final String POST_DRAFT_SAVED_V1 = "post.draft-saved.v1";

    public static final String POST_ARCHIVED_V1 = "post.archived.v1";
    public static final String POST_UNARCHIVED_V1 = "post.unarchived.v1";

    /**
     * A person was tagged on a published post, approved at once or waiting for their approval.
     *
     * <p>Carries {@code notify}: false for a tag a limit routed to the hidden requests, which is
     * stored and counted for the signals but never announced to the tagged person.
     */
    public static final String POST_TAG_CREATED_V1 = "post.tag.created.v1";

    public static final String POST_TAG_APPROVED_V1 = "post.tag.approved.v1";
    public static final String POST_TAG_REMOVED_V1 = "post.tag.removed.v1";

    public static final String POST_COLLABORATION_INVITED_V1 = "post.collaboration.invited.v1";
    public static final String POST_COLLABORATION_ACCEPTED_V1 = "post.collaboration.accepted.v1";
    public static final String POST_COLLABORATION_DECLINED_V1 = "post.collaboration.declined.v1";
    public static final String POST_COLLABORATION_LEFT_V1 = "post.collaboration.left.v1";

    /**
     * A caption mention was stored for an account.
     *
     * <p>Carries {@code notify}: true only at first publication and only when the policy allowed
     * the mention outright, so an edit, a limited target and a draft never produce a notification.
     */
    public static final String POST_MENTION_CREATED_V1 = "post.mention.created.v1";

    public static final String POST_LIVE_LIKED_V1 = "post.live.liked.v1";
    public static final String POST_LIVE_UNLIKED_V1 = "post.live.unliked.v1";

    private PostEventTypes() {}
}
