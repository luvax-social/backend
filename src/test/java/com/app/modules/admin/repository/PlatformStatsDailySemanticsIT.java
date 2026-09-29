package com.app.modules.admin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent.Row;
import com.app.modules.admin.repository.PlatformStatsAnalyticsRepository.SeriesPoint;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

/**
 * What the statistics store answers once there is no daily grain: a day is computed from the
 * half-hour buckets when a series is read, a flow by summing them and a gauge by taking the last
 * one, with day boundaries in UTC and a re-collected bucket replaced by its later computation.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.stats.enabled=false"
        })
@Testcontainers
class PlatformStatsDailySemanticsIT {

    private static final Instant DAY = Instant.parse("2026-07-01T00:00:00Z");

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        ClickHouseTestSupport.register(r, clickhouse);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "platform-stats-daily-it-secret-32-chars-min!");
        r.add("JWT_ISSUER", () -> "https://platform-stats-daily.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private PlatformStatsAnalyticsRepository repository;

    @BeforeEach
    void startFromAnEmptyStore() {
        assertThat(gate.attempt()).isTrue();
        truncate();
    }

    @AfterEach
    void cleanup() {
        truncate();
    }

    @Test
    void dailyFlow_isTheSumOfTheDaysBucketsAndBoundariesAreUtc() {
        Instant computed = DAY.plusSeconds(86_400);
        repository.insertBucket(DAY, computed, List.of(new Row("registrations", "", 2)));
        repository.insertBucket(
                DAY.plusSeconds(12 * 3600 + 1800),
                computed,
                List.of(new Row("registrations", "", 3)));
        // The last bucket of the day and the first of the next sit on either side of UTC midnight.
        repository.insertBucket(
                DAY.plusSeconds(23 * 3600 + 1800),
                computed,
                List.of(new Row("registrations", "", 5)));
        repository.insertBucket(
                DAY.plusSeconds(86_400), computed, List.of(new Row("registrations", "", 100)));

        List<SeriesPoint> series =
                repository.findDailyFlowSeries(
                        "registrations", utc(DAY), utc(DAY.plusSeconds(2 * 86_400)));

        assertThat(series)
                .extracting(SeriesPoint::bucketStart, SeriesPoint::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(utc(DAY), 10L),
                        org.assertj.core.groups.Tuple.tuple(utc(DAY.plusSeconds(86_400)), 100L));
    }

    @Test
    void dailyGauge_isTheLastBucketOfTheDay_andADimensionAbsentFromItReadsAsMissing() {
        Instant computed = DAY.plusSeconds(86_400);
        repository.insertBucket(
                DAY.plusSeconds(10 * 3600),
                computed,
                List.of(
                        new Row("users_by_status", "active", 4),
                        new Row("users_by_status", "banned", 1)));
        // By the end of the day nobody is banned, so the last bucket has no banned row. The old
        // roll-up kept the 10:00 value of banned; the end-of-day state is that there are none.
        repository.insertBucket(
                DAY.plusSeconds(23 * 3600 + 1800),
                computed,
                List.of(new Row("users_by_status", "active", 6)));

        List<SeriesPoint> series =
                repository.findDailyGaugeSeries(
                        "users_by_status", utc(DAY), utc(DAY.plusSeconds(86_400)));

        assertThat(series)
                .extracting(SeriesPoint::dimension, SeriesPoint::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("active", 6L));
    }

    @Test
    void aRecollectedBucket_isReplacedByItsLaterComputationWhateverOrderTheyArrivedIn() {
        Instant bucket = DAY.plusSeconds(3600);
        Instant early = bucket.plusSeconds(1800);
        Instant late = early.plusSeconds(600);
        repository.insertBucket(bucket, early, List.of(new Row("posts_total", "", 5)));
        repository.insertBucket(bucket, late, List.of(new Row("posts_total", "", 9)));
        // A stale copy delivered after the newer one must not win.
        repository.insertBucket(bucket, early, List.of(new Row("posts_total", "", 1)));

        List<SeriesPoint> series =
                repository.findHalfHourSeries(
                        "posts_total", utc(bucket), utc(bucket.plusSeconds(1800)));

        assertThat(series).extracting(SeriesPoint::value).containsExactly(9L);
    }

    @Test
    void newestBucket_isEmptyBeforeAnyBucketAndTheLatestStartAfter() {
        assertThat(repository.findNewestBucket()).isEmpty();

        repository.insertBucket(DAY, DAY.plusSeconds(1800), List.of(new Row("posts_total", "", 1)));
        repository.insertBucket(
                DAY.plusSeconds(1800),
                DAY.plusSeconds(3600),
                List.of(new Row("posts_total", "", 2)));

        assertThat(repository.findNewestBucket()).contains(utc(DAY.plusSeconds(1800)));
        assertThat(repository.findBucket(utc(DAY.plusSeconds(1800))))
                .extracting(PlatformStatsAnalyticsRepository.StatRow::value)
                .containsExactly(2L);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void truncate() {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement()) {
            statement.execute("TRUNCATE TABLE luvax_analytics.platform_stats");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
