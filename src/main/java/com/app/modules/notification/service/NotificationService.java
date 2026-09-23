package com.app.modules.notification.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.entity.enums.NotificationType;

public interface NotificationService {

    /**
     * Writes a notification if every guard passes, and publishes it to the recipient's live feed.
     *
     * <p>Guards, in order: the actor is not the recipient; the operator has not disabled the type
     * in {@code notification_type_configs}; the recipient has not switched the type off in {@code
     * user_settings}; the actor and recipient are not in a block either way. A platform notice has
     * no actor, so only the operator switch applies to it: a warning, a removal notice and a
     * support answer have no user toggle, because an account that could switch them off would be
     * disciplined or answered without being told.
     *
     * <p>Toggle mapping: {@code FOLLOW}/{@code FOLLOW_REQUEST} check {@code notify_follows}, {@code
     * LIKE_POST}/{@code LIKE_COMMENT} check {@code notify_likes}, {@code COMMENT_POST}/{@code
     * REPLY_COMMENT} check {@code notify_comments}, {@code MENTION_POST}/{@code MENTION_COMMENT}
     * check {@code notify_mentions}, {@code MESSAGE} checks {@code notify_messages}; {@code
     * STORY_VIEW} has no toggle.
     *
     * <p>An aggregatable type ({@link NotificationType#isAggregatable()}) joins the recipient's
     * open group for its target, which moves to the top of the feed and reads as unread and unseen
     * again; an actor already in that group changes nothing. Every other type writes one row.
     *
     * @param draft the notification
     * @return true when a row was written or a group gained the actor; false when a guard
     *     suppressed it or the actor was already in the group
     */
    boolean create(NotificationDraft draft);

    /**
     * Convenience for {@link #create(NotificationDraft)} with no message and no audit row.
     *
     * @param actorId user who acted; null for a platform notice
     * @param recipientId user who is told
     * @param type notification type
     * @param entityType polymorphic target type; null when the target is the recipient
     * @param entityId polymorphic target id; null when the target is the recipient
     * @param postId the post the target belongs to; null for non-content types
     * @return see {@link #create(NotificationDraft)}
     */
    boolean create(
            UUID actorId,
            UUID recipientId,
            NotificationType type,
            String entityType,
            UUID entityId,
            UUID postId);

    /**
     * Withdraws an actor from the notifications an action of theirs produced, after the action was
     * undone: an unlike, an unfollow, a cancelled or rejected follow request.
     *
     * <p>A retraction never moves a notification in the feed and never makes it unread or unseen
     * again. A notification left with no actor is removed from the feed. For {@code LIKE_POST},
     * {@code LIKE_COMMENT} and {@code STORY_VIEW} the actor leaves every group for {@code
     * targetId}, open or closed; for {@code FOLLOW} and {@code FOLLOW_REQUEST} the actor leaves
     * every follow row of the recipient, which covers a follow group, an approved request and a
     * pending one.
     *
     * @param actorId the user whose action was undone
     * @param recipientId the user who had been told
     * @param type the notification type the undone action produced
     * @param targetId the liked post or comment, or the viewed story; ignored for follow types
     */
    void retract(UUID actorId, UUID recipientId, NotificationType type, UUID targetId);

    /**
     * Applies the recipient's answer to a follow request.
     *
     * <p>An approval turns the request into a follow notification in place, keeping its position
     * and read state, so it reads "started following you" with a follow-back action and does not
     * re-alert. A rejection removes the request from the feed.
     *
     * @param requesterId the user who asked to follow
     * @param approverId the private account that answered, and the notification's recipient
     * @param approved true for an approval
     */
    void resolveFollowRequest(UUID requesterId, UUID approverId, boolean approved);

    /**
     * Removes each user of a newly blocked pair from the other's aggregated groups and pending
     * follow requests.
     *
     * <p>Other notifications between the pair stay stored and are hidden while the block lasts by
     * the read-time block filter, so lifting the block shows them again.
     *
     * @param blockerId the user who blocked
     * @param blockedId the user who was blocked
     */
    void onBlock(UUID blockerId, UUID blockedId);

    /**
     * Rewrites the verified-actor flag on every live notification whose newest actor is {@code
     * actorId}, from that account's current verification state.
     *
     * <p>Runs in bounded batches, each in its own transaction, so a prolific account never holds
     * one long write on the notifications table. Reading the current state rather than trusting the
     * event makes a reordered grant and revocation converge on the truth.
     *
     * @param actorId the account whose verification changed
     * @return the number of rows rewritten
     */
    int resyncActorVerified(UUID actorId);

    /**
     * Marks the notification as read. Throws {@link com.app.common.exception.AppException} with
     * {@link com.app.common.enums.ApiErrorCode#FORBIDDEN} if the notification does not exist or
     * belongs to a different recipient.
     *
     * @param notificationId notification to mark as read
     * @param recipientId authenticated user's id
     */
    void markAsRead(UUID notificationId, UUID recipientId);

    /**
     * Marks all unread notifications for the recipient as read in a single operation.
     *
     * @param recipientId authenticated user's id
     */
    void markAllAsRead(UUID recipientId);

    /**
     * Returns the count of unread notifications for the given recipient.
     *
     * @param recipientId authenticated user's id
     * @return count of notifications where is_read = false
     */
    long getUnreadCount(UUID recipientId);

    /**
     * Returns a cursor-paginated list of notifications for the recipient, ordered by creation time
     * descending.
     *
     * @param recipientId authenticated user's id
     * @param cursor opaque cursor of the last item on the previous page; null for first page
     * @param limit maximum number of items to return (max 100)
     * @return cursor page response containing notification items
     */
    CursorPageResponse<NotificationResponse> listNotifications(
            UUID recipientId, String cursor, int limit);
}
