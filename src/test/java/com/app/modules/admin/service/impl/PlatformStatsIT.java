package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.admin.service.PlatformStatsCollectionService;
import com.app.modules.admin.service.PlatformStatsRollupService;
import com.app.modules.admin.service.StatsBuckets;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Exercises collection, roll-up and retention against real SQL.
 *
 * <p>The aggregation rules live in statements rather than in Java, so a unit test with a mocked
 * repository would prove nothing about them. Everything asserted here is what the database actually
 * did.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class PlatformStatsIT {

    private static final Duration INTERVAL = Duration.ofMinutes(30);

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "platform-stats-it-secret-32-chars-min!!!!");
        registry.add("JWT_ISSUER", () -> "https://platformstats.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
        // The scheduled passes must not race the explicit calls this class makes.
        registry.add("app.stats.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformStatsCollectionService collectionService;
    @Autowired private PlatformStatsRollupService rollupService;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM platform_stats");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void collectBucket_writesGaugesAndFlowsForTheBucket() {
        OffsetDateTime bucket = pastBucket(4);
        createUser("stats_inside", "user", bucket.plusMinutes(5));
        createUser("stats_before", "user", bucket.minusHours(2));

        collectionService.collectBucket(bucket);

        // The gauge counts everything that existed by the end of the bucket.
        assertThat(valueOf(bucket, "users_total", "")).isEqualTo(2L);
        assertThat(valueOf(bucket, "users_by_role", "user")).isEqualTo(2L);
        assertThat(valueOf(bucket, "users_by_status", "active")).isEqualTo(2L);
        // The flow counts only what happened inside it.
        assertThat(valueOf(bucket, "registrations", "")).isEqualTo(1L);
    }

    @Test
    void collectBucket_gaugeIgnoresRowsCreatedAfterTheBucketEnded() {
        OffsetDateTime bucket = pastBucket(4);
        createUser("stats_later", "user", bucket.plusHours(1));

        collectionService.collectBucket(bucket);

        // Bounding the gauge by the bucket end rather than by "now" is what lets the job be re-run
        // for a past bucket without overwriting that bucket's history with the present.
        assertThat(valueOf(bucket, "users_total", "")).isZero();
    }

    @Test
    void collectBucket_rerun_upsertsRatherThanDuplicating() {
        OffsetDateTime bucket = pastBucket(4);
        createUser("stats_rerun", "user", bucket.plusMinutes(1));

        collectionService.collectBucket(bucket);
        collectionService.collectBucket(bucket);

        assertThat(rowCount(bucket, "registrations")).isEqualTo(1);
        assertThat(valueOf(bucket, "registrations", "")).isEqualTo(1L);
    }

    @Test
    void collectBucket_flowIsUnchangedByADeletionInsideTheWindow() {
        OffsetDateTime bucket = pastBucket(4);
        UUID author = createUser("stats_author", "user", bucket.minusDays(1));
        UUID post = createPost(author, bucket.plusMinutes(2));
        collectionService.collectBucket(bucket);
        long before = valueOf(bucket, "posts_created", "");

        // A moderation sweep after the fact. A flow derived by subtracting gauge snapshots would
        // now read as a negative number; a direct range count over the window does not move.
        jdbcTemplate.update(
                "UPDATE posts SET deleted_at = NOW(), status = 'removed' WHERE id = ?", post);
        collectionService.collectBucket(bucket);

        assertThat(before).isEqualTo(1L);
        assertThat(valueOf(bucket, "posts_created", "")).isEqualTo(1L);
        assertThat(rowCount(bucket, "posts_created")).isEqualTo(1);
        // The gauge does move, which is the point of the distinction.
        assertThat(valueOf(bucket, "posts_total", "")).isZero();
    }

    @Test
    void rollUpDay_sumsFlowsAndTakesTheLastBucketForGauges() {
        OffsetDateTime day = OffsetDateTime.parse("2026-07-01T00:00:00Z");
        // Three buckets: a flow of 2, 3 and 5, and a gauge climbing 10, 20, 30.
        plantFine(day.plusHours(0), "registrations", "", 2);
        plantFine(day.plusHours(1), "registrations", "", 3);
        plantFine(day.plusHours(2), "registrations", "", 5);
        plantFine(day.plusHours(0), "users_total", "", 10);
        plantFine(day.plusHours(1), "users_total", "", 20);
        plantFine(day.plusHours(2), "users_total", "", 30);

        rollupService.rollUpDay(day);

        assertThat(dailyValue(day, "registrations", "")).isEqualTo(10L);
        assertThat(dailyValue(day, "users_total", "")).isEqualTo(30L);
        // Stated as its own assertion so the test records the trap: summing 60 for "total users"
        // would be three times the real figure, and it looks plausible enough to ship.
        assertThat(dailyValue(day, "users_total", "")).isNotEqualTo(60L);
        assertThat(fineRowCount(day)).isZero();
    }

    @Test
    void rollUpDay_writesOneDailyRowPerMetricAndDimension() {
        OffsetDateTime day = OffsetDateTime.parse("2026-07-02T00:00:00Z");
        plantFine(day.plusHours(0), "users_by_status", "active", 4);
        plantFine(day.plusHours(0), "users_by_status", "banned", 1);
        plantFine(day.plusHours(1), "users_by_status", "active", 6);
        plantFine(day.plusHours(1), "users_by_status", "banned", 2);

        rollupService.rollUpDay(day);

        assertThat(dailyRowCount(day)).isEqualTo(2);
        assertThat(dailyValue(day, "users_by_status", "active")).isEqualTo(6L);
        assertThat(dailyValue(day, "users_by_status", "banned")).isEqualTo(2L);
    }

    @Test
    void rollUpDay_aFailureAfterTheAggregateLeavesTheFineBucketsIntact() {
        OffsetDateTime day = OffsetDateTime.parse("2026-07-03T00:00:00Z");
        plantFine(day.plusHours(0), "registrations", "", 7);
        jdbcTemplate.execute(
                "CREATE FUNCTION reject_stats_delete() RETURNS trigger AS $$"
                        + " BEGIN RAISE EXCEPTION 'delete refused'; END; $$ LANGUAGE plpgsql");
        jdbcTemplate.execute(
                "CREATE TRIGGER reject_stats_delete BEFORE DELETE ON platform_stats"
                        + " FOR EACH ROW EXECUTE FUNCTION reject_stats_delete()");
        try {
            assertThatThrownBy(() -> rollupService.rollUpDay(day)).isInstanceOf(Exception.class);

            // Both statements are in one transaction, so the aggregate rolled back with the delete.
            // Had the delete run first and the insert then failed, the day would be gone with
            // nothing able to reconstruct it, because this table has no backfill.
            assertThat(fineRowCount(day)).isEqualTo(1);
            assertThat(dailyRowCount(day)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_stats_delete ON platform_stats");
            jdbcTemplate.execute("DROP FUNCTION reject_stats_delete()");
        }
    }

    @Test
    void pruneDailyRows_removesRowsPastRetentionAndNothingNewer() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expired = now.minusDays(400).truncatedTo(ChronoUnit.DAYS);
        OffsetDateTime kept = now.minusDays(300).truncatedTo(ChronoUnit.DAYS);
        plantDaily(expired, "registrations", "", 1);
        plantDaily(kept, "registrations", "", 2);

        int pruned = rollupService.pruneDailyRows(now);

        assertThat(pruned).isEqualTo(1);
        assertThat(dailyBucketStarts()).containsExactly(kept);
    }

    private static OffsetDateTime pastBucket(int bucketsBack) {
        return StatsBuckets.floor(OffsetDateTime.now(ZoneOffset.UTC).toInstant(), INTERVAL)
                .minus(INTERVAL.multipliedBy(bucketsBack));
    }

    private UUID createUser(String username, String role, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified,"
                        + " created_at) VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE,"
                        + " TRUE, ?)",
                id,
                username,
                username + "@test.local",
                role,
                createdAt);
        return id;
    }

    private UUID createPost(UUID author, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts (user_id, caption, post_type, status, created_at)"
                        + " VALUES (?, 'caption', 'text', 'published', ?) RETURNING id",
                UUID.class,
                author,
                createdAt);
    }

    private void plantFine(
            OffsetDateTime bucketStart, String metricKey, String dimension, long value) {
        plant(bucketStart, "half_hour", metricKey, dimension, value);
    }

    private void plantDaily(
            OffsetDateTime bucketStart, String metricKey, String dimension, long value) {
        plant(bucketStart, "day", metricKey, dimension, value);
    }

    private void plant(
            OffsetDateTime bucketStart,
            String granularity,
            String metricKey,
            String dimension,
            long value) {
        jdbcTemplate.update(
                "INSERT INTO platform_stats"
                        + " (bucket_start, granularity, metric_key, dimension, value)"
                        + " VALUES (?, CAST(? AS stat_granularity), ?, ?, ?)",
                bucketStart,
                granularity,
                metricKey,
                dimension,
                value);
    }

    private long valueOf(OffsetDateTime bucket, String metricKey, String dimension) {
        List<Long> values =
                jdbcTemplate.queryForList(
                        "SELECT value FROM platform_stats WHERE bucket_start = ?"
                                + " AND granularity = 'half_hour' AND metric_key = ?"
                                + " AND dimension = ?",
                        Long.class,
                        bucket,
                        metricKey,
                        dimension);
        return values.isEmpty() ? 0L : values.get(0);
    }

    private long dailyValue(OffsetDateTime day, String metricKey, String dimension) {
        List<Long> values =
                jdbcTemplate.queryForList(
                        "SELECT value FROM platform_stats WHERE bucket_start = ?"
                                + " AND granularity = 'day' AND metric_key = ? AND dimension = ?",
                        Long.class,
                        day,
                        metricKey,
                        dimension);
        return values.isEmpty() ? 0L : values.get(0);
    }

    private int rowCount(OffsetDateTime bucket, String metricKey) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_stats WHERE bucket_start = ?"
                        + " AND granularity = 'half_hour' AND metric_key = ?",
                Integer.class,
                bucket,
                metricKey);
    }

    private int fineRowCount(OffsetDateTime day) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_stats WHERE granularity = 'half_hour'"
                        + " AND bucket_start >= ? AND bucket_start < ?",
                Integer.class,
                day,
                day.plusDays(1));
    }

    private int dailyRowCount(OffsetDateTime day) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_stats WHERE granularity = 'day'"
                        + " AND bucket_start = ?",
                Integer.class,
                day);
    }

    private List<OffsetDateTime> dailyBucketStarts() {
        return jdbcTemplate.queryForList(
                "SELECT bucket_start FROM platform_stats WHERE granularity = 'day'"
                        + " ORDER BY bucket_start",
                OffsetDateTime.class);
    }
}
