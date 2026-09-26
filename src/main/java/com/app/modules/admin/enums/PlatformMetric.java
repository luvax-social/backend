package com.app.modules.admin.enums;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Every metric {@code platform_stats} carries, with the statement that computes it.
 *
 * <p>Each statement selects exactly two columns, a dimension and a value, so one insert template
 * serves all of them. A metric with no breakdown selects the empty string as its dimension. {@code
 * GROUP BY} produces no row for an empty group, so a dimension nobody holds in a bucket has no row
 * rather than a row holding zero, and every reader treats a missing dimension as zero.
 *
 * <p>The distinction between the two kinds is the whole design and is not a detail of presentation.
 *
 * <p>A {@link Kind#GAUGE} is the state of the world at the end of the bucket. Its statement takes
 * one bind, the bucket end, and bounds the population by it. Bounding by the bucket end rather than
 * counting whatever exists when the job happens to run is what lets the job be re-run for a past
 * bucket without overwriting that bucket's history with today's numbers.
 *
 * <p>A {@link Kind#FLOW} is how much happened inside the bucket. Its statement takes two binds, the
 * bucket start and end, and counts rows created between them. Flows are never derived by
 * subtracting consecutive gauges: a deletion would make the difference negative, and a missed run
 * would fold two intervals into one bucket undetectably.
 *
 * <p>Gauges apply each table's liveness filter and flows do not, which is deliberate rather than an
 * inconsistency. A post removed by moderation stops counting towards "posts on the platform" and
 * never stops counting towards "posts created in that half hour", because the second is a record of
 * something that happened.
 */
public enum PlatformMetric {
    USERS_TOTAL(
            "users_total",
            Kind.GAUGE,
            "SELECT '' AS dimension, count(*) AS value FROM users"
                    + " WHERE created_at < ? AND deleted_at IS NULL"),
    USERS_BY_STATUS(
            "users_by_status",
            Kind.GAUGE,
            "SELECT CAST(status AS text) AS dimension, count(*) AS value FROM users"
                    + " WHERE created_at < ? AND deleted_at IS NULL GROUP BY status"),
    USERS_BY_ROLE(
            "users_by_role",
            Kind.GAUGE,
            "SELECT CAST(role AS text) AS dimension, count(*) AS value FROM users"
                    + " WHERE created_at < ? AND deleted_at IS NULL GROUP BY role"),
    POSTS_TOTAL(
            "posts_total",
            Kind.GAUGE,
            "SELECT '' AS dimension, count(*) AS value FROM posts"
                    + " WHERE created_at < ? AND deleted_at IS NULL AND status = 'published'"),
    COMMENTS_TOTAL(
            "comments_total",
            Kind.GAUGE,
            "SELECT '' AS dimension, count(*) AS value FROM comments"
                    + " WHERE created_at < ? AND deleted_at IS NULL"
                    + " AND admin_removed_at IS NULL"),
    // Bounded by expiry as well as by creation, because a story that has run out is no longer on
    // the platform in any sense a dashboard means by "stories".
    STORIES_TOTAL(
            "stories_total",
            Kind.GAUGE,
            "SELECT '' AS dimension, count(*) AS value FROM stories"
                    + " WHERE created_at < ? AND deleted_at IS NULL"
                    + " AND admin_removed_at IS NULL AND expires_at > ?"),
    REPORTS_BY_STATUS(
            "reports_by_status",
            Kind.GAUGE,
            "SELECT CAST(status AS text) AS dimension, count(*) AS value FROM reports"
                    + " WHERE created_at < ? GROUP BY status"),
    REPORTS_BY_REASON(
            "reports_by_reason",
            Kind.GAUGE,
            "SELECT CAST(report_reason AS text) AS dimension, count(*) AS value FROM reports"
                    + " WHERE created_at < ? GROUP BY report_reason"),
    REGISTRATIONS(
            "registrations",
            Kind.FLOW,
            "SELECT '' AS dimension, count(*) AS value FROM users"
                    + " WHERE created_at >= ? AND created_at < ?"),
    POSTS_CREATED(
            "posts_created",
            Kind.FLOW,
            "SELECT '' AS dimension, count(*) AS value FROM posts"
                    + " WHERE created_at >= ? AND created_at < ?"),
    COMMENTS_CREATED(
            "comments_created",
            Kind.FLOW,
            "SELECT '' AS dimension, count(*) AS value FROM comments"
                    + " WHERE created_at >= ? AND created_at < ?"),
    FOLLOWS_CREATED(
            "follows_created",
            Kind.FLOW,
            "SELECT '' AS dimension, count(*) AS value FROM follows"
                    + " WHERE created_at >= ? AND created_at < ?"),
    LIKES_CREATED(
            "likes_created",
            Kind.FLOW,
            "SELECT '' AS dimension, count(*) AS value FROM post_likes"
                    + " WHERE created_at >= ? AND created_at < ?"),
    ADMIN_ACTIONS_BY_TYPE(
            "admin_actions_by_type",
            Kind.FLOW,
            "SELECT CAST(action_type AS text) AS dimension, count(*) AS value FROM admin_actions"
                    + " WHERE created_at >= ? AND created_at < ? GROUP BY action_type");

    /**
     * How a metric behaves under aggregation, which is the only thing the roll-up needs to know.
     */
    public enum Kind {
        GAUGE,
        FLOW
    }

    private final String key;
    private final Kind kind;
    private final String selectSql;

    PlatformMetric(String key, Kind kind, String selectSql) {
        this.key = key;
        this.kind = kind;
        this.selectSql = selectSql;
    }

    /**
     * The metric's wire form, which is also its {@code platform_stats.metric_key} value.
     *
     * <p>Annotated so the enum crosses the wire as its key rather than its Java constant name, and
     * so the generated document enumerates the closed set instead of describing it in prose.
     *
     * @return the metric key
     */
    @JsonValue
    public String key() {
        return key;
    }

    /**
     * Resolves a wire key to its metric.
     *
     * @param value the wire key, case-insensitive and trimmed
     * @return the matching metric
     * @throws IllegalArgumentException when no metric carries that key, which Spring MVC surfaces
     *     as a 400 on a request parameter
     */
    @JsonCreator
    public static PlatformMetric fromJson(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("metric key is required");
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(metric -> metric.key.equals(normalized))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("unknown metric key '" + value + "'"));
    }

    public Kind kind() {
        return kind;
    }

    public String selectSql() {
        return selectSql;
    }

    /** Keys of every metric that sums across buckets. */
    public static List<String> flowKeys() {
        return Arrays.stream(values()).filter(m -> m.kind == Kind.FLOW).map(m -> m.key).toList();
    }

    /** Keys of every metric that must never be summed across buckets. */
    public static List<String> gaugeKeys() {
        return Arrays.stream(values()).filter(m -> m.kind == Kind.GAUGE).map(m -> m.key).toList();
    }
}
