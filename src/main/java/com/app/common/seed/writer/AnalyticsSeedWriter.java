package com.app.common.seed.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.app.common.seed.outbox.SeedOutboxBatchWriter;
import com.app.common.seed.time.SeedTimeline;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds platform statistics (90 days of half-hour buckets) and {@code user_events} (~10,000 rows
 * across the last 3 months).
 *
 * <p><b>Platform statistics</b>: each bucket is enqueued as one {@code
 * admin.platform-stats.collected.v1} outbox event, the event the collection job raises, so the
 * seeded series reaches ClickHouse through the same consumer production uses. Only half-hour
 * buckets are seeded, because a daily figure is computed from them when a series is read. Every
 * bucket this writer produces is fully closed before {@code referenceNow}: a bucket's start is at
 * least one full half-hour back, so it never writes "the bucket a process starts inside", matching
 * {@code admin/DATA_RULES.md}'s stated rule for {@link
 * com.app.modules.admin.service.impl.StatsCollectionJob}. Every metric key this writer produces is
 * one of {@link com.app.modules.admin.enums.PlatformMetric}'s own wire keys, verbatim - the two do
 * not share a source, so the key strings must be kept in sync by hand. Eight gauge metrics ({@code
 * users_total}, {@code posts_total}, {@code comments_total}, {@code stories_total}, and the four
 * dimensioned breakdowns {@code users_by_status}, {@code users_by_role}, {@code reports_by_status},
 * {@code reports_by_reason}) are written as synthetic monotonically-increasing snapshots anchored
 * on the real final row counts this seed run produced - never by differencing two gauges, which is
 * exactly the mistake {@code admin/DATA_RULES.md} documents as having shipped once already. A
 * dimensioned gauge writes one row per dimension value actually present in the final distribution,
 * never an explicit zero row for a value nobody holds, matching {@code PlatformMetric}'s own "GROUP
 * BY produces no row for an empty group" rule. Five flow metrics ({@code registrations}, {@code
 * posts_created}, {@code comments_created}, {@code follows_created}, {@code likes_created}) are
 * independent draws, never derived from the gauge series: each UTC day draws a total from its
 * per-day range and spreads it over the day's 48 buckets, so the daily figure computed from the
 * buckets lands inside that range. {@code PlatformMetric} also declares a sixth flow, {@code
 * admin_actions_by_type}, which this writer does not populate; see this class's "Known gap" note
 * below.
 *
 * <p><b>Known gap</b>: {@code admin_actions_by_type} is not seeded. Synthesizing a plausible
 * 90-day/half-hour distribution across all 27 {@code admin_action_type} values from the roughly 178
 * real, narratively-clustered {@code admin_actions} rows this seed authors would not be a faithful
 * representation of that data, so the timeseries endpoint reads back empty for that one metric
 * specifically; every other {@code PlatformMetric} value is populated.
 *
 * <p><b>{@code user_events}</b>: every row's {@code created_at} comes from {@link
 * SeedTimeline#userEventCreatedAt}, bounded to the last 90 days - comfortably inside the partition
 * range {@code database/schema.sql} declares through 2026-09, so no row here can land in {@code
 * user_events_default}. {@code entity_id} references a real {@code posts}/{@code users}/{@code
 * hashtags}/{@code stories} row read back from the database for every event type that carries one,
 * so a downstream reader resolving {@code entity_id} never dereferences a dangling id.
 */
@Slf4j
@Service
@Profile("seed & (dev | prod)")
@RequiredArgsConstructor
public class AnalyticsSeedWriter {

    // Deliberately separate from SeedTimeline's own Random stream (used only for timestamps): this
    // stream drives metric-value magnitudes and every user_events field but created_at.
    private static final long ANALYTICS_RANDOM_SEED = 8_836_215L;

    private static final int BUCKET_DAYS = 90;
    private static final int BUCKETS_PER_DAY = 48;
    private static final int BUCKET_EVENTS_PER_TRANSACTION = 300;
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);
    private static final int USER_EVENT_TARGET = 10_000;
    private static final int USER_EVENT_LOOKBACK_DAYS = 90;

    // Every key below is a com.app.modules.admin.enums.PlatformMetric wire key, verbatim - the
    // seed writer and the real StatsCollectionJob/AdminStatsService must agree on these or the
    // admin stats surface reads back empty for anything this writer produced.
    private static final String USERS_TOTAL_METRIC = "users_total";
    private static final String POSTS_TOTAL_METRIC = "posts_total";
    private static final String COMMENTS_TOTAL_METRIC = "comments_total";
    private static final String STORIES_TOTAL_METRIC = "stories_total";
    private static final String USERS_BY_STATUS_METRIC = "users_by_status";
    private static final String USERS_BY_ROLE_METRIC = "users_by_role";
    private static final String REPORTS_BY_STATUS_METRIC = "reports_by_status";
    private static final String REPORTS_BY_REASON_METRIC = "reports_by_reason";
    private static final List<String> FLOW_METRICS =
            List.of(
                    "registrations",
                    "posts_created",
                    "comments_created",
                    "follows_created",
                    "likes_created");
    private static final Map<String, int[]> FLOW_RANGE_PER_DAY =
            Map.of(
                    "registrations", new int[] {0, 3},
                    "posts_created", new int[] {5, 45},
                    "comments_created", new int[] {10, 120},
                    "follows_created", new int[] {5, 60},
                    "likes_created", new int[] {40, 400});

    // post_view is deliberately absent: SeedOutboxEmitter now emits post.viewed.v1 through the
    // real outbox, and the recommendation consumer writes the resulting user_events rows. Drawing
    // post_view here as well would put two rows in the table for one logical view, and the random
    // pairs this writer produces carry none of the band weighting or persona structure the
    // recommender trains on.
    private static final List<String> WEIGHTED_EVENT_TYPES =
            List.of(
                    "session_start",
                    "session_start",
                    "session_end",
                    "app_open",
                    "app_open",
                    "profile_view",
                    "search",
                    "hashtag_click",
                    "post_like",
                    "post_unlike",
                    "post_save",
                    "post_unsave",
                    "post_share",
                    "post_comment",
                    "story_view",
                    "story_reply",
                    "comment_like",
                    "comment_reply",
                    "profile_follow",
                    "profile_unfollow",
                    "message_send");
    private static final List<String> PLATFORMS = List.of("ios", "android", "web");

    private static final String INSERT_USER_EVENT_SQL =
            "INSERT INTO user_events (id, user_id, session_id, event_type, entity_type, entity_id,"
                    + " platform, created_at) VALUES (?, ?, ?, ?::event_type, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final SeedOutboxBatchWriter outboxBatchWriter;

    /**
     * Enqueues {@code BUCKET_DAYS * BUCKETS_PER_DAY} half-hour platform statistics buckets, and
     * inserts roughly {@value #USER_EVENT_TARGET} {@code user_events} rows spread across the last
     * {@value #USER_EVENT_LOOKBACK_DAYS} days.
     *
     * <p>Must run last: the statistics gauge series anchors on the final row counts every earlier
     * writer produced, and {@code user_events}'s entity references are read back from {@code
     * posts}, {@code hashtags} and {@code stories}.
     */
    public void write(SeedTimeline timeline) {
        Random random = new Random(ANALYTICS_RANDOM_SEED);

        FinalCounts finalCounts =
                new FinalCounts(
                        countRows("users"),
                        countRows("posts"),
                        countRows("comments WHERE deleted_at IS NULL AND admin_removed_at IS NULL"),
                        countRows(
                                "stories WHERE deleted_at IS NULL AND admin_removed_at IS NULL"
                                        + " AND expires_at > NOW()"),
                        countsByColumn("users", "status", "deleted_at IS NULL"),
                        countsByColumn("users", "role", "deleted_at IS NULL"),
                        countsByColumn("reports", "status", null),
                        countsByColumn("reports", "report_reason", null));

        int buckets = writeBuckets(finalCounts, timeline, random);
        log.info("[seed] platform_stats: {} half_hour bucket events enqueued", buckets);

        int eventRows = writeUserEvents(timeline, random);
        log.info("[seed] user_events: {} rows written", eventRows);
    }

    // The real final row counts every bucket's gauge ramps toward, dimensioned exactly the way
    // com.app.modules.admin.enums.PlatformMetric's GROUP BY queries dimension them - one map entry
    // per dimension value actually present, matching "a dimension nobody holds has no row" from
    // that enum's own Javadoc.
    private record FinalCounts(
            long users,
            long posts,
            long comments,
            long stories,
            Map<String, Long> usersByStatus,
            Map<String, Long> usersByRole,
            Map<String, Long> reportsByStatus,
            Map<String, Long> reportsByReason) {}

    private Map<String, Long> countsByColumn(String table, String column, String extraWhere) {
        String where = extraWhere == null ? "" : " WHERE " + extraWhere;
        String sql =
                "SELECT CAST("
                        + column
                        + " AS text) AS k, COUNT(*) AS c FROM "
                        + table
                        + where
                        + " GROUP BY "
                        + column;
        return jdbc.query(
                sql,
                rs -> {
                    Map<String, Long> result = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("k"), rs.getLong("c"));
                    }
                    return result;
                });
    }

    // Builds every gauge and flow row of 90 days of half-hour buckets, each ending strictly before
    // referenceNow, and enqueues one collected-bucket event per bucket. The gauge curve is a linear
    // ramp toward the real final counts with a small random jitter layered on top, oldest bucket
    // first. A flow is drawn once per UTC day and spread over that day's buckets, so the daily sum
    // the read path computes falls inside the per-day range.
    private int writeBuckets(FinalCounts finalCounts, SeedTimeline timeline, Random random) {
        int bucketCount = BUCKET_DAYS * BUCKETS_PER_DAY;
        Instant bucketFloor = alignedFloor(timeline.referenceNow(), HALF_HOUR);
        List<PlatformStatsCollectedEvent.Bucket> buckets = new ArrayList<>(bucketCount);
        Instant currentDay = null;
        Map<String, int[]> dayFlows = Map.of();
        // i = 1 is the most recently completed bucket, i = bucketCount the oldest; the loop walks
        // oldest-to-newest so the gauge ramp's progress fraction increases monotonically with time.
        for (int i = bucketCount; i >= 1; i--) {
            Instant bucketStart = bucketFloor.minus(HALF_HOUR.multipliedBy(i));
            Instant computedAt = bucketStart.plus(HALF_HOUR).plus(Duration.ofMinutes(1));
            double progress = 1.0 - ((double) (i - 1) / bucketCount);
            Instant day = bucketStart.truncatedTo(ChronoUnit.DAYS);
            if (!day.equals(currentDay)) {
                currentDay = day;
                dayFlows = drawDayFlows(random);
            }
            int slot = (int) Duration.between(day, bucketStart).dividedBy(HALF_HOUR);

            List<PlatformStatsCollectedEvent.Row> rows = new ArrayList<>();
            rows.add(gaugeRow(USERS_TOTAL_METRIC, "", finalCounts.users(), progress, random));
            rows.add(gaugeRow(POSTS_TOTAL_METRIC, "", finalCounts.posts(), progress, random));
            rows.add(gaugeRow(COMMENTS_TOTAL_METRIC, "", finalCounts.comments(), progress, random));
            rows.add(gaugeRow(STORIES_TOTAL_METRIC, "", finalCounts.stories(), progress, random));
            addDimensionedGaugeRows(
                    rows, USERS_BY_STATUS_METRIC, finalCounts.usersByStatus(), progress, random);
            addDimensionedGaugeRows(
                    rows, USERS_BY_ROLE_METRIC, finalCounts.usersByRole(), progress, random);
            addDimensionedGaugeRows(
                    rows,
                    REPORTS_BY_STATUS_METRIC,
                    finalCounts.reportsByStatus(),
                    progress,
                    random);
            addDimensionedGaugeRows(
                    rows,
                    REPORTS_BY_REASON_METRIC,
                    finalCounts.reportsByReason(),
                    progress,
                    random);
            for (String flowMetric : FLOW_METRICS) {
                rows.add(
                        new PlatformStatsCollectedEvent.Row(
                                flowMetric, "", dayFlows.get(flowMetric)[slot]));
            }
            buckets.add(new PlatformStatsCollectedEvent.Bucket(bucketStart, computedAt, rows));
        }
        for (int from = 0; from < buckets.size(); from += BUCKET_EVENTS_PER_TRANSACTION) {
            int to = Math.min(from + BUCKET_EVENTS_PER_TRANSACTION, buckets.size());
            outboxBatchWriter.emitPlatformStatsBatch(buckets.subList(from, to));
        }
        return buckets.size();
    }

    // One UTC day of every flow metric: a total drawn from the metric's per-day range, then spread
    // over the day's 48 buckets one unit at a time.
    private Map<String, int[]> drawDayFlows(Random random) {
        Map<String, int[]> flows = new java.util.LinkedHashMap<>();
        for (String flowMetric : FLOW_METRICS) {
            int[] dailyRange = FLOW_RANGE_PER_DAY.get(flowMetric);
            int total = dailyRange[0] + random.nextInt(Math.max(1, dailyRange[1] - dailyRange[0]));
            int[] perBucket = new int[BUCKETS_PER_DAY];
            for (int unit = 0; unit < total; unit++) {
                perBucket[random.nextInt(BUCKETS_PER_DAY)]++;
            }
            flows.put(flowMetric, perBucket);
        }
        return flows;
    }

    private PlatformStatsCollectedEvent.Row gaugeRow(
            String metricKey, String dimension, long finalCount, double progress, Random random) {
        return new PlatformStatsCollectedEvent.Row(
                metricKey, dimension, rampedGauge(finalCount, progress, random));
    }

    // One row per dimension value actually present in the final distribution - a value with zero
    // final rows gets no row at any bucket, matching PlatformMetric's own "GROUP BY produces no row
    // for an empty group" rule rather than writing an explicit zero.
    private void addDimensionedGaugeRows(
            List<PlatformStatsCollectedEvent.Row> rows,
            String metricKey,
            Map<String, Long> finalByDimension,
            double progress,
            Random random) {
        for (Map.Entry<String, Long> entry : finalByDimension.entrySet()) {
            rows.add(gaugeRow(metricKey, entry.getKey(), entry.getValue(), progress, random));
        }
    }

    private long rampedGauge(long finalCount, double progress, Random random) {
        double base = finalCount * (0.4 + 0.6 * progress);
        double jitter = 1.0 + (random.nextDouble() - 0.5) * 0.04;
        long value = Math.round(base * jitter);
        return Math.max(0, Math.min(value, finalCount));
    }

    private Instant alignedFloor(Instant instant, Duration span) {
        long spanSeconds = span.getSeconds();
        long epochSeconds = instant.getEpochSecond();
        return Instant.ofEpochSecond(epochSeconds - (epochSeconds % spanSeconds));
    }

    private int writeUserEvents(SeedTimeline timeline, Random random) {
        List<UUID> userIds = fetchIds("SELECT id FROM users");
        List<UUID> postIds = fetchIds("SELECT id FROM posts");
        List<UUID> hashtagIds = fetchIds("SELECT id FROM hashtags");
        List<UUID> storyIds = fetchIds("SELECT id FROM stories");
        if (userIds.isEmpty()) {
            throw new IllegalStateException(
                    "AnalyticsSeedWriter: no users rows found - UserSeedWriter must run before"
                            + " AnalyticsSeedWriter");
        }

        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < USER_EVENT_TARGET; i++) {
            UUID userId = userIds.get(random.nextInt(userIds.size()));
            String eventType =
                    WEIGHTED_EVENT_TYPES.get(random.nextInt(WEIGHTED_EVENT_TYPES.size()));
            String platform = PLATFORMS.get(random.nextInt(PLATFORMS.size()));
            Instant createdAt = timeline.userEventCreatedAt(USER_EVENT_LOOKBACK_DAYS);

            EntityRef entityRef =
                    resolveEntityRef(
                            eventType, postIds, hashtagIds, storyIds, userIds, userId, random);
            rows.add(
                    new Object[] {
                        UUID.randomUUID(),
                        userId,
                        UUID.randomUUID(),
                        eventType,
                        entityRef == null ? null : entityRef.entityType(),
                        entityRef == null ? null : entityRef.entityId(),
                        platform,
                        Timestamp.from(createdAt)
                    });
        }
        jdbc.batchUpdate(INSERT_USER_EVENT_SQL, rows, rows.size(), this::bindUserEventRow);
        return rows.size();
    }

    private record EntityRef(String entityType, UUID entityId) {}

    // Every event type this writer draws from WEIGHTED_EVENT_TYPES carries an entity reference
    // except session_start/session_end/app_open/search, which describe the session or the query
    // rather than one target, and comment_reply/message_send, which have no id pool fetched into
    // this method (no commentIds/conversationIds/messageIds argument exists - adding one for two
    // event types would be a new database read out of proportion to what this writer needs). A
    // candidate pool that happens to be empty (e.g. no stories seeded yet) degrades to no entity
    // reference rather than throwing - entity_id carries no FK, so a null is always valid, just
    // less descriptive.
    private EntityRef resolveEntityRef(
            String eventType,
            List<UUID> postIds,
            List<UUID> hashtagIds,
            List<UUID> storyIds,
            List<UUID> userIds,
            UUID actingUserId,
            Random random) {
        return switch (eventType) {
            case "post_like",
                            "post_unlike",
                            "post_save",
                            "post_unsave",
                            "post_share",
                            "post_comment" ->
                    randomEntityRef("post", postIds, random);
            case "hashtag_click" -> randomEntityRef("hashtag", hashtagIds, random);
            case "story_view", "story_reply" -> randomEntityRef("story", storyIds, random);
            case "profile_view", "profile_follow", "profile_unfollow" -> {
                UUID other = randomOtherUser(userIds, actingUserId, random);
                yield other == null ? null : new EntityRef("user", other);
            }
            case "comment_like", "comment_reply", "message_send", "session_end" -> null;
            default -> null;
        };
    }

    private EntityRef randomEntityRef(String type, List<UUID> pool, Random random) {
        if (pool.isEmpty()) {
            return null;
        }
        return new EntityRef(type, pool.get(random.nextInt(pool.size())));
    }

    private UUID randomOtherUser(List<UUID> userIds, UUID actingUserId, Random random) {
        if (userIds.size() < 2) {
            return null;
        }
        UUID candidate;
        do {
            candidate = userIds.get(random.nextInt(userIds.size()));
        } while (candidate.equals(actingUserId));
        return candidate;
    }

    private long countRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private List<UUID> fetchIds(String sql) {
        return jdbc.query(sql, (rs, rowNum) -> (UUID) rs.getObject("id"));
    }

    private void bindUserEventRow(PreparedStatement ps, Object[] row) throws SQLException {
        ps.setObject(1, row[0]);
        ps.setObject(2, row[1]);
        ps.setObject(3, row[2]);
        ps.setString(4, (String) row[3]);
        if (row[4] == null) {
            ps.setNull(5, java.sql.Types.VARCHAR);
        } else {
            ps.setString(5, (String) row[4]);
        }
        if (row[5] == null) {
            ps.setNull(6, java.sql.Types.OTHER);
        } else {
            ps.setObject(6, row[5]);
        }
        ps.setString(7, (String) row[6]);
        ps.setTimestamp(8, (Timestamp) row[7]);
    }
}
