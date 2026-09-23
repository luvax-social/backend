package com.app.modules.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationFilter;
import com.app.modules.notification.entity.enums.NotificationType;

/**
 * Every read of the activity feed: pages per filter, a single row for the live push, the newest row
 * ({@code head}), the bounded unseen count, and the actors shown on each row.
 *
 * <p>All of them share one visibility predicate, {@link #VISIBLE}, so the badge, the head, the list
 * and the live push can never disagree about whether a row exists. A row is visible to its
 * recipient when it is not deleted and it is either a platform notice or has at least one actor who
 * is active, not deleted, and not in a block with the recipient in either direction. The predicate
 * lives in SQL rather than after the fetch because filtering in Java would return short pages and
 * break the invariant that only the last page is short.
 *
 * <p>The predicate reads {@code users} and {@code blocks} directly, extending the block join this
 * module's queries already carried. No other module's repository is injected: this is one SQL
 * statement per read, the same as before.
 */
@Repository
public class NotificationFeedRepository {

    /** Rows read per badge count: one more than the badge shows, to know it is capped. */
    public static final int UNSEEN_COUNT_LIMIT = 100;

    /** Actors fetched per row: two are shown, the third covers one hidden by a later block. */
    public static final int DISPLAY_ACTOR_LIMIT = 3;

    private static final String ACTOR_VISIBLE =
            "u.deleted_at IS NULL AND u.status = 'active'"
                    + " AND NOT EXISTS (SELECT 1 FROM blocks b"
                    + " WHERE (b.blocker_id = :viewer AND b.blocked_id = na.actor_id)"
                    + " OR (b.blocker_id = na.actor_id AND b.blocked_id = :viewer))";

    static final String VISIBLE =
            "n.recipient_id = :viewer AND n.deleted_at IS NULL"
                    + " AND (n.category = 'system' OR EXISTS (SELECT 1 FROM notification_actors na"
                    + " JOIN users u ON u.id = na.actor_id WHERE na.notification_id = n.id AND "
                    + ACTOR_VISIBLE
                    + "))";

    private static final String ROW_COLUMNS =
            "n.id, n.type::text AS type, n.category::text AS category, n.entity_type,"
                    + " n.entity_id, n.post_id, n.message, n.admin_action_id, n.read_at,"
                    + " n.activity_at, n.created_at, n.actor_count";

    private final JdbcClient jdbc;

    public NotificationFeedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A feed row as stored; actors and previews are resolved separately.
     *
     * @param actorCount every actor of the row, before blocks and inactive accounts are removed
     */
    public record FeedRow(
            UUID id,
            NotificationType type,
            NotificationCategory category,
            String entityType,
            UUID entityId,
            UUID postId,
            String message,
            UUID adminActionId,
            OffsetDateTime readAt,
            OffsetDateTime activityAt,
            OffsetDateTime createdAt,
            int actorCount) {}

    /** A position in the feed. */
    public record Key(OffsetDateTime activityAt, UUID id) {}

    /**
     * One page of the recipient's feed for a filter, newest first.
     *
     * <p>Pending follow requests are never rows of the feed under any filter; they are summarised
     * by the pinned entry instead. Each filter's predicate matches the partial index built for it,
     * so a filter whose matches are rare does not scan unrelated rows.
     *
     * @param before the position of the last row of the previous page, or null for the first page
     * @param limit rows to return; callers ask for one more than the page size to learn whether a
     *     further page exists
     */
    public List<FeedRow> findPage(UUID viewerId, NotificationFilter filter, Key before, int limit) {
        String sql =
                "SELECT "
                        + ROW_COLUMNS
                        + " FROM notifications n WHERE "
                        + VISIBLE
                        + " AND n.type <> 'follow_request' AND "
                        + filter.predicate()
                        + (before == null
                                ? ""
                                : " AND (n.activity_at, n.id) < (:beforeAt, :beforeId)")
                        + " ORDER BY n.activity_at DESC, n.id DESC LIMIT :limit";
        JdbcClient.StatementSpec statement =
                jdbc.sql(sql).param("viewer", viewerId).param("limit", limit);
        if (before != null) {
            statement =
                    statement.param("beforeAt", before.activityAt()).param("beforeId", before.id());
        }
        return statement.query(NotificationFeedRepository::mapRow).list();
    }

    /**
     * A single row of the recipient's, if it is visible to them; for the live push, which renders
     * one row through the same pipeline as a page. Includes pending follow requests, which the
     * caller routes to the pinned entry rather than the list.
     */
    public Optional<FeedRow> findVisible(UUID viewerId, UUID notificationId) {
        return jdbc.sql(
                        "SELECT "
                                + ROW_COLUMNS
                                + " FROM notifications n WHERE n.id = :id AND "
                                + VISIBLE)
                .param("viewer", viewerId)
                .param("id", notificationId)
                .query(NotificationFeedRepository::mapRow)
                .optional();
    }

    /**
     * The newest visible row of the recipient's across every type, pending follow requests
     * included: the position a client advances the seen watermark to after rendering the first
     * page, since the pinned request entry is rendered with it.
     */
    public Optional<Key> findHead(UUID viewerId) {
        return jdbc.sql(
                        "SELECT n.activity_at, n.id FROM notifications n WHERE "
                                + VISIBLE
                                + " ORDER BY n.activity_at DESC, n.id DESC LIMIT 1")
                .param("viewer", viewerId)
                .query(
                        (rs, rowNum) ->
                                new Key(
                                        rs.getObject("activity_at", OffsetDateTime.class),
                                        rs.getObject("id", UUID.class)))
                .optional();
    }

    /**
     * Visible rows newer than {@code seen}, pending follow requests included, counted up to {@link
     * #UNSEEN_COUNT_LIMIT}. At most that many index entries are read however large the backlog.
     *
     * @param seen the seen watermark, or null when the recipient has never been shown the feed
     */
    public long countUnseen(UUID viewerId, Key seen) {
        String sql =
                "SELECT count(*) FROM (SELECT 1 FROM notifications n WHERE "
                        + VISIBLE
                        + (seen == null ? "" : " AND (n.activity_at, n.id) > (:seenAt, :seenId)")
                        + " LIMIT :cap) unseen";
        JdbcClient.StatementSpec statement =
                jdbc.sql(sql).param("viewer", viewerId).param("cap", UNSEEN_COUNT_LIMIT);
        if (seen != null) {
            statement = statement.param("seenAt", seen.activityAt()).param("seenId", seen.id());
        }
        return statement.query(Long.class).single();
    }

    /**
     * The actors shown on each row, newest first, at most {@link #DISPLAY_ACTOR_LIMIT} per row,
     * leaving out accounts that are inactive, deleted or in a block with the viewer.
     *
     * @return actor ids per notification id, in display order; a row with no visible actor is
     *     absent
     */
    public Map<UUID, List<UUID>> findDisplayActors(
            UUID viewerId, Collection<UUID> notificationIds) {
        if (notificationIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UUID>> actors = new LinkedHashMap<>();
        jdbc.sql(
                        "SELECT t.nid AS notification_id, x.actor_id FROM"
                                + " unnest(ARRAY[:ids]::uuid[]) AS t(nid) CROSS JOIN LATERAL"
                                + " (SELECT na.actor_id, na.acted_at FROM notification_actors na"
                                + " JOIN users u ON u.id = na.actor_id"
                                + " WHERE na.notification_id = t.nid AND "
                                + ACTOR_VISIBLE
                                + " ORDER BY na.acted_at DESC, na.actor_id DESC LIMIT :limit) x"
                                + " ORDER BY t.nid, x.acted_at DESC, x.actor_id DESC")
                .param("viewer", viewerId)
                .param("ids", notificationIds)
                .param("limit", DISPLAY_ACTOR_LIMIT)
                .query(
                        (rs, rowNum) -> {
                            actors.computeIfAbsent(
                                            rs.getObject("notification_id", UUID.class),
                                            id -> new java.util.ArrayList<>())
                                    .add(rs.getObject("actor_id", UUID.class));
                            return null;
                        })
                .list();
        return actors;
    }

    /**
     * How many of each row's actors the viewer cannot see, so "and N others" never counts an
     * account hidden by a block or by its status.
     *
     * @return hidden actor count per notification id; a row with none is absent
     */
    public Map<UUID, Integer> countHiddenActors(UUID viewerId, Collection<UUID> notificationIds) {
        if (notificationIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> hidden = new HashMap<>();
        jdbc.sql(
                        "SELECT na.notification_id, count(*) AS hidden FROM notification_actors na"
                                + " JOIN users u ON u.id = na.actor_id"
                                + " WHERE na.notification_id IN (:ids) AND NOT ("
                                + ACTOR_VISIBLE
                                + ") GROUP BY na.notification_id")
                .param("viewer", viewerId)
                .param("ids", notificationIds)
                .query(
                        (rs, rowNum) -> {
                            hidden.put(
                                    rs.getObject("notification_id", UUID.class),
                                    rs.getInt("hidden"));
                            return null;
                        })
                .list();
        return hidden;
    }

    private static FeedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FeedRow(
                rs.getObject("id", UUID.class),
                NotificationType.fromJson(rs.getString("type")),
                NotificationCategory.fromValue(rs.getString("category")),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getObject("post_id", UUID.class),
                rs.getString("message"),
                rs.getObject("admin_action_id", UUID.class),
                rs.getObject("read_at", OffsetDateTime.class),
                rs.getObject("activity_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getInt("actor_count"));
    }
}
