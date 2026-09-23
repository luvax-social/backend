package com.app.modules.notification.repository;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationDraft;

/**
 * Every write to the activity feed: single rows, aggregated groups, actor membership, retraction,
 * follow-request resolution and the verified-actor resync.
 *
 * <p>Plain SQL rather than JPA, because each write depends on PostgreSQL behaviour the entity
 * manager does not expose: {@code ON CONFLICT} against a partial unique index to serialise
 * concurrent first actors on one group, {@code xmax} to tell an insert from a join, and {@code
 * clock_timestamp()} rather than the transaction start time for the feed sort key.
 *
 * <p>Timestamps come from the database clock only, so application instances with skewed clocks
 * cannot disagree about the order of the feed or the position of a watermark.
 */
@Repository
public class NotificationAggregationRepository {

    private final JdbcClient jdbc;

    public NotificationAggregationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The row a group write landed on, whether it opened that group, and whether the actor joined.
     */
    public record GroupWrite(UUID id, boolean opened, boolean joined) {}

    /**
     * A row an actor was removed from.
     *
     * @param id the row
     * @param emptied true when no actor remains, in which case the row is now soft-deleted
     * @param actorId the newest remaining actor, or the removed actor when the row emptied
     */
    public record Removal(UUID id, boolean emptied, UUID actorId) {}

    /**
     * Inserts a single, non-aggregated notification with the database clock as its sort key.
     *
     * @return the new row's id
     */
    public UUID insertSingle(
            NotificationDraft draft, NotificationCategory category, boolean actorVerified) {
        return jdbc.sql(
                        "INSERT INTO notifications (recipient_id, actor_id, type, category,"
                                + " entity_type, entity_id, post_id, message, admin_action_id,"
                                + " actor_verified, activity_at) VALUES (:recipientId, :actorId,"
                                + " CAST(:type AS notification_type), CAST(:category AS"
                                + " notification_category), :entityType, :entityId, :postId,"
                                + " :message, :adminActionId, :actorVerified, clock_timestamp())"
                                + " RETURNING id")
                .param("recipientId", draft.recipientId())
                .param("actorId", draft.actorId())
                .param("type", draft.type().toJson())
                .param("category", category.toJson())
                .param("entityType", draft.entityType())
                .param("entityId", draft.entityId())
                .param("postId", draft.postId())
                .param("message", draft.message())
                .param("adminActionId", draft.adminActionId())
                .param("actorVerified", actorVerified)
                .query(UUID.class)
                .single();
    }

    /**
     * Adds an actor to a notification. A repeated membership is a no-op, which is what makes a
     * duplicate or reordered event harmless.
     *
     * @return true when the actor was not already a member
     */
    public boolean addMember(UUID notificationId, UUID actorId) {
        return jdbc.sql(
                        "INSERT INTO notification_actors (notification_id, actor_id, acted_at)"
                                + " VALUES (:notificationId, :actorId, clock_timestamp())"
                                + " ON CONFLICT (notification_id, actor_id) DO NOTHING"
                                + " RETURNING actor_id")
                .param("notificationId", notificationId)
                .param("actorId", actorId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    /**
     * Opens the recipient's group for {@code aggregationKey}, or joins the open one, and adds the
     * actor to it.
     *
     * <p>Four statements in the caller's transaction. An open group older than {@code window} is
     * closed first, lazily, so no scheduler is needed. The insert then targets the partial unique
     * index {@code uq_notifications_open_group}: of two concurrent first actors one inserts and the
     * other waits for its commit and takes the conflict branch, so both land in one group. The
     * conflict branch is a deliberate no-op that only locks the row and returns its id. Only when
     * the actor is new to an existing group does the group move to the newest actor and become
     * unread again; a repeated actor changes nothing.
     *
     * @param draft the event; its actor must be non-null
     * @param category the draft type's category
     * @param aggregationKey the group key, see {@link NotificationType#aggregationKey(UUID)}
     * @param window how long a group accepts new actors from its first
     * @param actorVerified whether the actor holds a verified badge
     */
    public GroupWrite upsertGroup(
            NotificationDraft draft,
            NotificationCategory category,
            String aggregationKey,
            Duration window,
            boolean actorVerified) {
        jdbc.sql(
                        "UPDATE notifications SET is_group_open = FALSE"
                                + " WHERE recipient_id = :recipientId"
                                + " AND aggregation_key = :aggregationKey"
                                + " AND is_group_open AND deleted_at IS NULL"
                                + " AND group_started_at <= clock_timestamp()"
                                + " - make_interval(secs => :windowSeconds)")
                .param("recipientId", draft.recipientId())
                .param("aggregationKey", aggregationKey)
                .param("windowSeconds", (double) window.toSeconds())
                .update();

        GroupWrite landed =
                jdbc.sql(
                                "INSERT INTO notifications (recipient_id, actor_id, type,"
                                        + " category, entity_type, entity_id, post_id,"
                                        + " aggregation_key, is_group_open, group_started_at,"
                                        + " actor_verified, activity_at) VALUES (:recipientId,"
                                        + " :actorId, CAST(:type AS notification_type),"
                                        + " CAST(:category AS notification_category),"
                                        + " :entityType, :entityId, :postId, :aggregationKey,"
                                        + " TRUE, clock_timestamp(), :actorVerified,"
                                        + " clock_timestamp())"
                                        + " ON CONFLICT (recipient_id, aggregation_key)"
                                        + " WHERE is_group_open AND deleted_at IS NULL"
                                        + " DO UPDATE SET is_group_open = notifications.is_group_open"
                                        + " RETURNING id, (xmax = 0) AS inserted")
                        .param("recipientId", draft.recipientId())
                        .param("actorId", draft.actorId())
                        .param("type", draft.type().toJson())
                        .param("category", category.toJson())
                        .param("entityType", draft.entityType())
                        .param("entityId", draft.entityId())
                        .param("postId", draft.postId())
                        .param("aggregationKey", aggregationKey)
                        .param("actorVerified", actorVerified)
                        .query(
                                (rs, rowNum) ->
                                        new GroupWrite(
                                                rs.getObject("id", UUID.class),
                                                rs.getBoolean("inserted"),
                                                false))
                        .single();

        boolean joined = addMember(landed.id(), draft.actorId());
        if (joined && !landed.opened()) {
            jdbc.sql(
                            "UPDATE notifications SET actor_id = :actorId,"
                                    + " actor_verified = :actorVerified, read_at = NULL"
                                    + " WHERE id = :id")
                    .param("actorId", draft.actorId())
                    .param("actorVerified", actorVerified)
                    .param("id", landed.id())
                    .update();
        }
        return new GroupWrite(landed.id(), landed.opened(), joined);
    }

    /**
     * Stamps the row with the database clock as its feed position.
     *
     * <p>Called as the last statement of every write that surfaces a row, so the sort key is the
     * last clock read before commit. That narrows the window in which a row can commit below a
     * watermark a client has already advanced past to commit latency.
     */
    public void touchActivity(UUID notificationId) {
        jdbc.sql("UPDATE notifications SET activity_at = clock_timestamp() WHERE id = :id")
                .param("id", notificationId)
                .update();
    }

    /**
     * Removes an actor from the recipient's live groups for one aggregation key, open or closed.
     *
     * @return the rows the actor was removed from, settled
     */
    public List<Removal> removeMemberFromGroups(
            UUID recipientId, UUID actorId, String aggregationKey) {
        List<UUID> touched =
                jdbc.sql(
                                "DELETE FROM notification_actors na USING notifications n"
                                        + " WHERE na.notification_id = n.id"
                                        + " AND n.recipient_id = :recipientId"
                                        + " AND n.aggregation_key = :aggregationKey"
                                        + " AND n.deleted_at IS NULL AND na.actor_id = :actorId"
                                        + " RETURNING n.id")
                        .param("recipientId", recipientId)
                        .param("aggregationKey", aggregationKey)
                        .param("actorId", actorId)
                        .query(UUID.class)
                        .list();
        return settle(touched, actorId);
    }

    /**
     * Removes an actor from every live follow-category row of the recipient: a follow group, a
     * follow converted from an approved request, and a pending request.
     *
     * @return the rows the actor was removed from, settled
     */
    public List<Removal> removeMemberFromFollowRows(UUID recipientId, UUID actorId) {
        List<UUID> touched =
                jdbc.sql(
                                "DELETE FROM notification_actors na USING notifications n"
                                        + " WHERE na.notification_id = n.id"
                                        + " AND n.recipient_id = :recipientId"
                                        + " AND n.category = 'follow'"
                                        + " AND n.deleted_at IS NULL AND na.actor_id = :actorId"
                                        + " RETURNING n.id")
                        .param("recipientId", recipientId)
                        .param("actorId", actorId)
                        .query(UUID.class)
                        .list();
        return settle(touched, actorId);
    }

    /**
     * Removes an actor from the recipient's aggregated groups and pending follow requests, one
     * direction of a block.
     *
     * <p>Other rows are left alone: they stay hidden by the read-time block filter while the block
     * lasts and reappear if it is lifted, as before this release.
     *
     * @return the rows the actor was removed from, settled
     */
    public List<Removal> removeMemberOnBlock(UUID recipientId, UUID actorId) {
        List<UUID> touched =
                jdbc.sql(
                                "DELETE FROM notification_actors na USING notifications n"
                                        + " WHERE na.notification_id = n.id"
                                        + " AND n.recipient_id = :recipientId"
                                        + " AND n.deleted_at IS NULL AND na.actor_id = :actorId"
                                        + " AND (n.aggregation_key IS NOT NULL"
                                        + " OR n.type = 'follow_request')"
                                        + " RETURNING n.id")
                        .param("recipientId", recipientId)
                        .param("actorId", actorId)
                        .query(UUID.class)
                        .list();
        return settle(touched, actorId);
    }

    // A retraction never moves a row in the feed or marks it unread again: activity_at and
    // read_at are untouched. A row left with no actor is soft-deleted and its group closed, so
    // the next actor on that target starts a new group rather than reviving an emptied one.
    private List<Removal> settle(Collection<UUID> notificationIds, UUID removedActorId) {
        if (notificationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        "UPDATE notifications n SET"
                                + " deleted_at = CASE WHEN n.actor_count = 0 THEN now()"
                                + " ELSE n.deleted_at END,"
                                + " is_group_open = CASE WHEN n.actor_count = 0 THEN FALSE"
                                + " ELSE n.is_group_open END,"
                                + " actor_id = CASE WHEN n.actor_count > 0"
                                + " AND n.actor_id = :removedActorId THEN"
                                + " (SELECT na.actor_id FROM notification_actors na"
                                + " WHERE na.notification_id = n.id"
                                + " ORDER BY na.acted_at DESC, na.actor_id DESC LIMIT 1)"
                                + " ELSE n.actor_id END"
                                + " WHERE n.id IN (:ids)"
                                + " RETURNING n.id, n.deleted_at IS NOT NULL AS emptied,"
                                + " n.actor_id")
                .param("removedActorId", removedActorId)
                .param("ids", notificationIds)
                .query(
                        (rs, rowNum) ->
                                new Removal(
                                        rs.getObject("id", UUID.class),
                                        rs.getBoolean("emptied"),
                                        rs.getObject("actor_id", UUID.class)))
                .list();
    }

    /** Sets whether the row's newest actor is verified, after that actor changed. */
    public void setActorVerified(UUID notificationId, boolean actorVerified) {
        jdbc.sql("UPDATE notifications SET actor_verified = :verified WHERE id = :id")
                .param("verified", actorVerified)
                .param("id", notificationId)
                .update();
    }

    /** The recipient's live pending request from {@code requesterId}, if one was written. */
    public Optional<UUID> findLiveFollowRequest(UUID recipientId, UUID requesterId) {
        return jdbc.sql(
                        "SELECT id FROM notifications WHERE recipient_id = :recipientId"
                                + " AND category = 'follow' AND type = 'follow_request'"
                                + " AND actor_id = :requesterId AND deleted_at IS NULL"
                                + " ORDER BY activity_at DESC, id DESC LIMIT 1")
                .param("recipientId", recipientId)
                .param("requesterId", requesterId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Turns an approved request into a follow notification in place.
     *
     * <p>Position, read state and seen state are kept: the recipient just approved it, so it must
     * not re-alert. It is not joined to a follow group for the same reason.
     *
     * @return true when a live request row was converted
     */
    public boolean convertRequestToFollow(UUID notificationId) {
        return jdbc.sql(
                                "UPDATE notifications SET type = 'follow' WHERE id = :id"
                                        + " AND type = 'follow_request' AND deleted_at IS NULL")
                        .param("id", notificationId)
                        .update()
                > 0;
    }

    /**
     * Rewrites {@code actor_verified} on at most {@code batchSize} live rows whose newest actor is
     * {@code actorId} and whose flag disagrees with {@code verified}.
     *
     * <p>Bounded so the caller can commit between batches; repeat until it returns 0.
     *
     * @return the number of rows rewritten
     */
    public int resyncActorVerified(UUID actorId, boolean verified, int batchSize) {
        return jdbc.sql(
                        "UPDATE notifications SET actor_verified = :verified"
                                + " WHERE id IN (SELECT n.id FROM notification_actors na"
                                + " JOIN notifications n ON n.id = na.notification_id"
                                + " WHERE na.actor_id = :actorId AND n.actor_id = :actorId"
                                + " AND n.actor_verified <> :verified AND n.deleted_at IS NULL"
                                + " LIMIT :batchSize)")
                .param("verified", verified)
                .param("actorId", actorId)
                .param("batchSize", batchSize)
                .update();
    }
}
