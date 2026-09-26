package com.app.modules.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.app.modules.notification.repository.NotificationFeedRepository.Key;

/**
 * The per-user seen watermark ({@code notification_seen_states}).
 *
 * <p>Every timestamp is the database's, never the JVM's, so instances with skewed clocks agree on
 * where a watermark is and when a session gap has passed.
 */
@Repository
public class NotificationSeenStateRepository {

    private final JdbcClient jdbc;

    public NotificationSeenStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A user's watermarks.
     *
     * @param seen the newest position the user has been shown; null before the first advance
     * @param previous the boundary of the "new" section for the current visit; null until a visit
     *     has ended
     */
    public record SeenState(Key seen, Key previous) {

        public static final SeenState NONE = new SeenState(null, null);
    }

    public Optional<SeenState> find(UUID userId) {
        return jdbc.sql(
                        "SELECT seen_activity_at, seen_id, previous_activity_at, previous_id"
                                + " FROM notification_seen_states WHERE user_id = :userId")
                .param("userId", userId)
                .query(NotificationSeenStateRepository::mapState)
                .optional();
    }

    /**
     * Advances the user's seen watermark to the rendered row {@code notificationId}, in one
     * statement.
     *
     * <p>The row must be the user's; otherwise nothing is written and the result is empty. The
     * position written is clamped to the least of the client's {@code activityAt}, the row's
     * current {@code activity_at} and the database clock, so neither a forged timestamp nor a
     * skewed client clock can move the watermark past rows the client has not been shown. The
     * advance is monotonic: a stale tab reporting an older row never moves the watermark back.
     *
     * <p>{@code previous} is rotated to the old {@code seen} only on the first advance after {@code
     * sessionGap} of inactivity, so the "new" section keeps its rows across further advances and
     * reloads within one visit, and becomes exactly what arrived since the last visit on the next.
     *
     * @return the watermarks after the advance, or empty when the row is not the user's
     */
    public Optional<SeenState> advance(
            UUID userId, OffsetDateTime activityAt, UUID notificationId, Duration sessionGap) {
        return jdbc.sql(
                        "WITH target AS (SELECT LEAST(CAST(:activityAt AS timestamptz),"
                                + " n.activity_at, now()) AS activity_at, n.id"
                                + " FROM notifications n WHERE n.id = :id"
                                + " AND n.recipient_id = :userId)"
                                + " INSERT INTO notification_seen_states AS s (user_id,"
                                + " seen_activity_at, seen_id, previous_activity_at, previous_id,"
                                + " advanced_at) SELECT :userId, target.activity_at, target.id,"
                                + " NULL, NULL, now() FROM target"
                                + " ON CONFLICT (user_id) DO UPDATE SET"
                                + " previous_activity_at = CASE WHEN s.advanced_at < now()"
                                + " - make_interval(secs => :gapSeconds)"
                                + " THEN s.seen_activity_at ELSE s.previous_activity_at END,"
                                + " previous_id = CASE WHEN s.advanced_at < now()"
                                + " - make_interval(secs => :gapSeconds)"
                                + " THEN s.seen_id ELSE s.previous_id END,"
                                + " seen_activity_at = CASE WHEN s.seen_activity_at IS NULL"
                                + " OR (EXCLUDED.seen_activity_at, EXCLUDED.seen_id)"
                                + " > (s.seen_activity_at, s.seen_id)"
                                + " THEN EXCLUDED.seen_activity_at ELSE s.seen_activity_at END,"
                                + " seen_id = CASE WHEN s.seen_activity_at IS NULL"
                                + " OR (EXCLUDED.seen_activity_at, EXCLUDED.seen_id)"
                                + " > (s.seen_activity_at, s.seen_id)"
                                + " THEN EXCLUDED.seen_id ELSE s.seen_id END,"
                                + " advanced_at = now()"
                                + " RETURNING s.seen_activity_at, s.seen_id,"
                                + " s.previous_activity_at, s.previous_id")
                .param("userId", userId)
                .param("activityAt", activityAt)
                .param("id", notificationId)
                .param("gapSeconds", (double) sessionGap.toSeconds())
                .query(NotificationSeenStateRepository::mapState)
                .optional();
    }

    private static SeenState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new SeenState(
                key(rs, "seen_activity_at", "seen_id"),
                key(rs, "previous_activity_at", "previous_id"));
    }

    private static Key key(ResultSet rs, String timeColumn, String idColumn) throws SQLException {
        UUID id = rs.getObject(idColumn, UUID.class);
        return id == null ? null : new Key(rs.getObject(timeColumn, OffsetDateTime.class), id);
    }
}
