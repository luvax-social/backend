package com.app.modules.notification.service;

import java.util.Optional;
import java.util.UUID;

import com.app.modules.notification.dto.request.AdvanceSeenRequest;
import com.app.modules.notification.dto.request.NotificationPositionRequest;
import com.app.modules.notification.dto.response.NotificationItemResponse;
import com.app.modules.notification.dto.response.NotificationPageResponse;
import com.app.modules.notification.dto.response.NotificationReadStateResponse;
import com.app.modules.notification.dto.response.NotificationStateResponse;
import com.app.modules.notification.dto.response.ReadAllResponse;
import com.app.modules.notification.entity.enums.NotificationFilter;
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
     * Returns the account's feed state: the bounded unseen badge, the seen and previous watermarks,
     * and the pinned follow-request entry.
     *
     * <p>The badge counts visible notifications newer than the seen watermark, pending follow
     * requests included, reading at most 100 rows. A pending request therefore lights the badge
     * once, when it arrives, not for as long as it stays pending.
     *
     * @param userId the caller
     * @return the state; never null
     */
    NotificationStateResponse getState(UUID userId);

    /**
     * Advances the caller's seen watermark to the newest notification they rendered, clears the
     * badge up to it, and publishes the new state to the caller's other open tabs.
     *
     * <p>The position is clamped to the row's current feed position and to the database clock, and
     * never moves backwards. The "new" boundary rotates to the previous watermark only on the first
     * advance after the configured session gap.
     *
     * @param userId the caller
     * @param request the rendered row's {@code activityAt} and id
     * @return the state after the advance
     * @throws com.app.common.exception.AppException {@code FORBIDDEN} when the row is not the
     *     caller's
     */
    NotificationStateResponse advanceSeen(UUID userId, AdvanceSeenRequest request);

    /**
     * Returns one page of the caller's activity feed for a filter, newest first, every row
     * hydrated.
     *
     * <p>Pending follow requests are never rows, under any filter. The first page carries {@code
     * head}. Each row is marked new when it is above the watermark bounding this visit's new
     * section; the client never decides that from its own clock.
     *
     * @param userId the caller
     * @param filter the chip
     * @param cursor the previous page's end cursor; null for the first page
     * @param limit rows per page
     * @return the page
     * @throws com.app.common.exception.AppException {@code INVALID_CURSOR} for a malformed cursor
     */
    NotificationPageResponse listFeed(
            UUID userId, NotificationFilter filter, String cursor, int limit);

    /**
     * Returns one notification of the caller's, hydrated exactly as on a page, if it is still
     * visible to them; for the live push.
     *
     * @param userId the recipient
     * @param notificationId the notification
     * @return the item, or empty when it is deleted, not the caller's, or hidden by a block or
     *     account status
     */
    Optional<NotificationItemResponse> findItem(UUID userId, UUID notificationId);

    /**
     * Marks one of the caller's notifications read. Idempotent: a read notification keeps its
     * original read time.
     *
     * @return the notification id and its read time
     * @throws com.app.common.exception.AppException {@code FORBIDDEN} when the notification is not
     *     the caller's or was deleted
     */
    NotificationReadStateResponse markRead(UUID userId, UUID notificationId);

    /**
     * Marks one of the caller's notifications unread. Idempotent.
     *
     * @return the notification id with a null read time
     * @throws com.app.common.exception.AppException {@code FORBIDDEN} when the notification is not
     *     the caller's or was deleted
     */
    NotificationReadStateResponse markUnread(UUID userId, UUID notificationId);

    /**
     * Marks read every visible, unread notification of the caller's at or below {@code upTo}, the
     * newest row the client rendered, so a notification that arrived after the client looked stays
     * unread.
     *
     * @return how many notifications changed
     */
    ReadAllResponse markReadUpTo(UUID userId, NotificationPositionRequest upTo);

    /**
     * Removes one of the caller's notifications from their feed (a soft delete) and closes its
     * group, so a later like on the same post starts a new group. Idempotent.
     *
     * @throws com.app.common.exception.AppException {@code FORBIDDEN} when the notification is not
     *     the caller's
     */
    void delete(UUID userId, UUID notificationId);
}
