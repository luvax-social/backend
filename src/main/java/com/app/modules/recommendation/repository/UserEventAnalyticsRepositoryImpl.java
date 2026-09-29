package com.app.modules.recommendation.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Repository;

import com.app.common.analytics.ClickHouseOperations;
import com.app.modules.recommendation.enums.UserEventType;

@Repository
public class UserEventAnalyticsRepositoryImpl implements UserEventAnalyticsRepository {

    // The recorder's insert does not wait for the flush: a row lost to a data error or a crash
    // inside the flush window is the accepted price of never slowing a request, and ClickHouse's
    // own failed-insert counter is what surfaces those losses.
    private static final String RECORD_SQL =
            String.join(
                    " ",
                    "INSERT INTO user_events",
                    "(user_id, event_type, entity_type, entity_id, metadata, created_at)",
                    "SETTINGS async_insert = 1, wait_for_async_insert = 0",
                    "VALUES (:userId, :eventType, :entityType, :entityId, :metadata, :createdAt)");

    // wait_for_async_insert = 1 makes the call return only once the part is written, so the message
    // acknowledgement that follows can never precede a durable row. The busy timeout comes from the
    // writer's settings profile, so it can be tuned in ClickHouse without a deploy.
    private static final String ENGAGEMENT_SQL =
            String.join(
                    " ",
                    "INSERT INTO user_events",
                    "(id, user_id, event_type, entity_type, entity_id, created_at,",
                    "feedback_type, feedback_value)",
                    "SETTINGS async_insert = 1, wait_for_async_insert = 1",
                    "VALUES (:id, :userId, :eventType, :entityType, :entityId, :createdAt,",
                    ":feedbackType, :feedbackValue)");

    private static final String IMPORT_SQL =
            String.join(
                    " ",
                    "INSERT INTO user_events",
                    "(id, user_id, event_type, entity_type, entity_id, metadata, created_at)",
                    "SETTINGS async_insert = 1, wait_for_async_insert = 1",
                    "VALUES (:id, :userId, :eventType, :entityType, :entityId, :metadata,",
                    ":createdAt)");

    private static final String PAGE_SQL =
            String.join(
                    " ",
                    "SELECT id, user_id, event_type, entity_type, entity_id, metadata, created_at",
                    "FROM user_events FINAL",
                    "WHERE created_at >= :from AND created_at < :to");

    // The window is the leading predicate on every read because it is what lets the monthly
    // partitions outside it be skipped; the sorting key leads with the user, so a read that names
    // one is cheap and one that does not is an administrator page bounded to thirty days.
    private static final String READ_SET_SQL =
            String.join(
                    " ",
                    "SELECT entity_id FROM user_events",
                    "WHERE user_id = :userId AND event_type = :eventType",
                    "AND entity_id IS NOT NULL",
                    "AND created_at >= :from AND created_at < :to",
                    "ORDER BY created_at DESC LIMIT :limit");

    // Per (user, post) first, so a post's decayed weight is summed once and joined to its hashtags
    // in PostgreSQL afterwards; the result is identical to weighting each (event, hashtag) pair.
    // The weights and the exponential decay are the ones the PostgreSQL statement used, and each
    // reversal carries the negation of its action, so a like followed by an unlike cancels.
    // max_result_rows is lifted because a batch read legitimately returns more rows than the
    // reader profile's default cap allows; its memory is still bounded by the profile.
    private static final String AFFINITY_SQL =
            String.join(
                    " ",
                    "SELECT user_id, 'post' AS target_kind, entity_id AS target_id,",
                    "sum(transform(toString(event_type),",
                    "['post_save', 'post_share', 'post_comment', 'post_like', 'post_view',",
                    "'post_unsave', 'post_unlike'],",
                    "[4.0, 3.0, 3.0, 2.0, 0.25, -4.0, -2.0], 0.0)",
                    "* exp(log(0.5) * dateDiff('microsecond', created_at,",
                    "toDateTime64(:windowEnd, 6, 'UTC')) / (:halfLifeSeconds * 1e6)))",
                    "AS contribution, count() AS event_count",
                    "FROM user_events FINAL",
                    "WHERE created_at >= :windowStart AND created_at < :windowEnd",
                    "AND entity_type = 'post' AND entity_id IS NOT NULL",
                    "AND event_type IN ('post_save', 'post_share', 'post_comment', 'post_like',",
                    "'post_view', 'post_unsave', 'post_unlike')",
                    "GROUP BY user_id, entity_id",
                    "UNION ALL",
                    "SELECT user_id, 'hashtag', entity_id,",
                    "sum(3.0 * exp(log(0.5) * dateDiff('microsecond', created_at,",
                    "toDateTime64(:windowEnd, 6, 'UTC')) / (:halfLifeSeconds * 1e6))),",
                    "count()",
                    "FROM user_events FINAL",
                    "WHERE created_at >= :windowStart AND created_at < :windowEnd",
                    "AND event_type = 'hashtag_click' AND entity_type = 'hashtag'",
                    "AND entity_id IS NOT NULL",
                    "GROUP BY user_id, entity_id",
                    "SETTINGS max_result_rows = 0, max_execution_time = 120");

    private static final String FEEDBACK_SQL =
            String.join(
                    " ",
                    "SELECT feedback_type, user_id, entity_id, sum(feedback_value) AS total,",
                    "max(created_at) AS latest",
                    "FROM user_events FINAL",
                    "WHERE user_id IN (:userIds) AND feedback_type IS NOT NULL",
                    "AND entity_id IS NOT NULL",
                    "GROUP BY feedback_type, user_id, entity_id",
                    "SETTINGS max_result_rows = 0, max_execution_time = 120");

    private final ClickHouseOperations clickHouse;

    public UserEventAnalyticsRepositoryImpl(ClickHouseOperations clickHouse) {
        this.clickHouse = clickHouse;
    }

    @Override
    public void recordDetached(
            UUID userId,
            UserEventType eventType,
            String entityType,
            UUID entityId,
            String metadata,
            OffsetDateTime createdAt) {
        clickHouse.write(
                "user_events.record",
                client ->
                        client.sql(RECORD_SQL)
                                .param("userId", userId)
                                .param("eventType", eventType.toJson())
                                .param("entityType", entityType)
                                .param("entityId", entityId)
                                .param("metadata", metadata == null ? "" : metadata)
                                .param("createdAt", createdAt.withOffsetSameInstant(ZoneOffset.UTC))
                                .update());
    }

    @Override
    public void insertEngagement(
            UUID eventId,
            UUID userId,
            UserEventType eventType,
            String entityType,
            UUID entityId,
            OffsetDateTime occurredAt,
            String feedbackType,
            double feedbackValue) {
        clickHouse.write(
                "user_events.insert_engagement",
                client ->
                        client.sql(ENGAGEMENT_SQL)
                                .param("id", eventId)
                                .param("userId", userId)
                                .param("eventType", eventType.toJson())
                                .param("entityType", entityType)
                                .param("entityId", entityId)
                                .param(
                                        "createdAt",
                                        occurredAt.withOffsetSameInstant(ZoneOffset.UTC))
                                .param("feedbackType", feedbackType)
                                .param("feedbackValue", feedbackValue)
                                .update());
    }

    @Override
    public void insertImported(
            UUID eventId,
            UUID userId,
            UserEventType eventType,
            String entityType,
            UUID entityId,
            String metadata,
            OffsetDateTime createdAt) {
        clickHouse.write(
                "user_events.insert_imported",
                client ->
                        client.sql(IMPORT_SQL)
                                .param("id", eventId)
                                .param("userId", userId)
                                .param("eventType", eventType.toJson())
                                .param("entityType", entityType)
                                .param("entityId", entityId)
                                .param("metadata", metadata == null ? "" : metadata)
                                .param("createdAt", createdAt.withOffsetSameInstant(ZoneOffset.UTC))
                                .update());
    }

    @Override
    public List<UserEventRow> findPage(
            UUID userId,
            OffsetDateTime from,
            OffsetDateTime to,
            UserEventType eventType,
            OffsetDateTime cursorCreatedAt,
            UUID cursorId,
            int limit) {
        StringBuilder sql = new StringBuilder(PAGE_SQL);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", from);
        params.put("to", to);
        if (userId != null) {
            sql.append(" AND user_id = :userId");
            params.put("userId", userId);
        }
        if (eventType != null) {
            sql.append(" AND event_type = :eventType");
            params.put("eventType", eventType.toJson());
        }
        if (cursorCreatedAt != null && cursorId != null) {
            // The same shape as the audit log's listing: the identifier is compared as text, so a
            // cursor means the same thing on every store, and the leading bound is what lets the
            // sorting key skip everything newer than the cursor.
            sql.append(
                    " AND created_at <= :cursorCreatedAt AND (created_at < :cursorCreatedAt OR"
                            + " (created_at = :cursorCreatedAt AND toString(id) < :cursorId))");
            params.put("cursorCreatedAt", cursorCreatedAt);
            params.put("cursorId", cursorId.toString());
        }
        sql.append(" ORDER BY created_at DESC, toString(id) DESC LIMIT :limit");
        params.put("limit", limit);
        return clickHouse.read(
                "user_events.page",
                client ->
                        client.sql(sql.toString())
                                .params(params)
                                .query(UserEventAnalyticsRepositoryImpl::toRow)
                                .list());
    }

    @Override
    public List<UUID> findRecentEntityIds(
            UUID userId,
            UserEventType eventType,
            OffsetDateTime from,
            OffsetDateTime to,
            int limit) {
        return clickHouse.read(
                "user_events.read_set",
                client ->
                        client.sql(READ_SET_SQL)
                                .param("userId", userId)
                                .param("eventType", eventType.toJson())
                                .param("from", from)
                                .param("to", to)
                                .param("limit", limit)
                                .query((rows, rowNumber) -> rows.getObject("entity_id", UUID.class))
                                .list());
    }

    @Override
    public void streamAffinitySignals(
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            long halfLifeSeconds,
            int batchSize,
            Consumer<List<AffinitySignal>> sink) {
        try {
            clickHouse.readBatch(
                    "user_events.affinity_signals",
                    client -> {
                        List<AffinitySignal> batch = new ArrayList<>(batchSize);
                        client.sql(AFFINITY_SQL)
                                .param("windowStart", windowStart)
                                .param("windowEnd", windowEnd)
                                .param("halfLifeSeconds", halfLifeSeconds)
                                .query(
                                        rows -> {
                                            batch.add(toSignal(rows));
                                            if (batch.size() >= batchSize) {
                                                deliver(sink, batch);
                                            }
                                        });
                        deliver(sink, batch);
                        return null;
                    });
        } catch (SinkFailure failure) {
            throw failure.original();
        }
    }

    @Override
    public List<FeedbackTotal> findFeedbackTotals(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return clickHouse.readBatch(
                "user_events.feedback_totals",
                client ->
                        client.sql(FEEDBACK_SQL)
                                .param("userIds", userIds)
                                .query(UserEventAnalyticsRepositoryImpl::toFeedbackTotal)
                                .list());
    }

    // The sink writes to PostgreSQL. A failure there must reach the caller as itself: left to
    // propagate through the ClickHouse call it would be mistaken for a ClickHouse failure and
    // translated into an outage.
    private static void deliver(Consumer<List<AffinitySignal>> sink, List<AffinitySignal> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<AffinitySignal> copy = List.copyOf(batch);
        batch.clear();
        try {
            sink.accept(copy);
        } catch (RuntimeException e) {
            throw new SinkFailure(e);
        }
    }

    private static UserEventRow toRow(ResultSet rows, int rowNumber) throws SQLException {
        return new UserEventRow(
                rows.getObject("id", UUID.class),
                rows.getObject("user_id", UUID.class),
                UserEventType.fromJson(rows.getString("event_type")),
                rows.getString("entity_type"),
                rows.getObject("entity_id", UUID.class),
                rows.getString("metadata"),
                rows.getObject("created_at", OffsetDateTime.class)
                        .withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static AffinitySignal toSignal(ResultSet rows) throws SQLException {
        return new AffinitySignal(
                rows.getObject("user_id", UUID.class),
                rows.getString("target_kind"),
                rows.getObject("target_id", UUID.class),
                rows.getDouble("contribution"),
                rows.getLong("event_count"));
    }

    private static FeedbackTotal toFeedbackTotal(ResultSet rows, int rowNumber)
            throws SQLException {
        return new FeedbackTotal(
                rows.getString("feedback_type"),
                rows.getObject("user_id", UUID.class),
                rows.getObject("entity_id", UUID.class),
                rows.getDouble("total"),
                rows.getObject("latest", OffsetDateTime.class)
                        .withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static final class SinkFailure extends RuntimeException {

        SinkFailure(RuntimeException original) {
            super(original);
        }

        RuntimeException original() {
            return (RuntimeException) getCause();
        }
    }
}
