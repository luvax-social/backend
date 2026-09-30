package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The administrative statistics endpoints over the ClickHouse store: the stored snapshot rather
 * than a live count, daily series computed from half-hour buckets, and the typed 503 when the store
 * is down.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "management.health.elasticsearch.enabled=false",
            // A paused server accepts the connection and never answers, so the socket timeout is
            // what turns the hang into a failure the endpoint can answer with a 503.
            "app.analytics.clickhouse.reader.socket-timeout=PT1S",
            "app.analytics.clickhouse.connection-timeout=PT1S"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class AdminStatsControllerIT {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        ClickHouseTestSupport.register(registry, clickhouse);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "admin-stats-it-secret-32-chars-minimum!!!");
        registry.add("JWT_ISSUER", () -> "https://adminstats.it.local");
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
        // The scheduled collection must not overwrite the snapshots these tests plant.
        registry.add("app.stats.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private CircuitBreakerRegistry breakers;

    private record TestUser(UUID id, String token) {}

    @BeforeEach
    void startFromAHealthyStore() {
        assertThat(gate.attempt()).isTrue();
        breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME).reset();
    }

    @AfterEach
    void cleanup() {
        try {
            clickhouse.getDockerClient().unpauseContainerCmd(clickhouse.getContainerId()).exec();
        } catch (RuntimeException notPaused) {
            // The container is not paused when a test failed before pausing it.
        }
        clickHouse("TRUNCATE TABLE luvax_analytics.platform_stats");
        jdbcTemplate.update("DELETE FROM hashtags");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void currentStats_moderator_returnsForbidden() {
        TestUser moderator = createUser("stats_current_mod", "moderator");

        assertThat(getWithAuth("/api/v1/admin/stats/current", moderator).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void timeseries_moderator_returnsForbidden() {
        TestUser moderator = createUser("stats_series_mod", "moderator");

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/stats/timeseries?metric=registrations",
                                        moderator)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void currentStats_readsTheStoredSnapshotRatherThanCountingLive() {
        TestUser admin = createUser("stats_snapshot_admin", "admin");
        OffsetDateTime bucket = OffsetDateTime.parse("2026-08-01T00:00:00Z");
        // A figure no live count could produce: the accounts table holds one row. If the endpoint
        // answered 999999 it read the snapshot; if it answered 1 it counted, which at production
        // size is seconds of work on a request path.
        plant(bucket, "users_total", "", 999_999);
        plant(bucket, "users_by_status", "active", 999_998);
        plant(bucket, "posts_total", "", 4242);

        ResponseEntity<Map> response = getWithAuth("/api/v1/admin/stats/current", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(((Number) data.get("totalUsers")).longValue()).isEqualTo(999_999L);
        assertThat(((Number) data.get("totalPosts")).longValue()).isEqualTo(4242L);
        assertThat(data.get("computedAt")).isNotNull();
        assertThat(data.get("bucketStart")).isNotNull();
        assertThat(data.get("topHashtagsLive")).isEqualTo(true);
    }

    @Test
    void currentStats_readsTheNewestBucketWhenSeveralExist() {
        TestUser admin = createUser("stats_newest_admin", "admin");
        plant(OffsetDateTime.parse("2026-08-01T00:00:00Z"), "users_total", "", 100);
        plant(OffsetDateTime.parse("2026-08-01T00:30:00Z"), "users_total", "", 200);

        ResponseEntity<Map> response = getWithAuth("/api/v1/admin/stats/current", admin);

        assertThat(((Number) dataOf(response).get("totalUsers")).longValue()).isEqualTo(200L);
    }

    @Test
    void currentStats_beforeAnyCollection_returnsZerosAndNullTimestamps() {
        TestUser admin = createUser("stats_empty_admin", "admin");

        ResponseEntity<Map> response = getWithAuth("/api/v1/admin/stats/current", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("bucketStart")).isNull();
        assertThat(data.get("computedAt")).isNull();
        assertThat(((Number) data.get("totalUsers")).longValue()).isZero();
    }

    @Test
    void currentStats_topHashtagsAreComputedLiveAndOrderedByUse() {
        TestUser admin = createUser("stats_tags_admin", "admin");
        insertHashtag("quiet", 3);
        insertHashtag("loud", 90);
        insertHashtag("banned_loud", 500, "banned");

        ResponseEntity<Map> response = getWithAuth("/api/v1/admin/stats/current", admin);

        List<Map<String, Object>> tags = topHashtagsOf(response);
        assertThat(tags).extracting(tag -> tag.get("name")).containsExactly("loud", "quiet");
    }

    @Test
    void timeseries_noBounds_defaultsToTwentyFourHoursOfHalfHourBuckets() {
        TestUser admin = createUser("stats_default_admin", "admin");
        OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        plant(recent, "registrations", "", 11);

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/admin/stats/timeseries?metric=registrations", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("granularity")).isEqualTo("half_hour");
        OffsetDateTime from = OffsetDateTime.parse((String) data.get("from"));
        OffsetDateTime to = OffsetDateTime.parse((String) data.get("to"));
        assertThat(java.time.Duration.between(from, to).toHours()).isEqualTo(24);
        assertThat(pointsOf(response)).hasSize(1);
    }

    @Test
    void timeseries_windowOlderThanTheHorizon_isServedAsDailyPointsComputedFromBuckets() {
        TestUser admin = createUser("stats_daily_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime day = now.minusDays(100).truncatedTo(ChronoUnit.DAYS);
        // A flow: the daily figure is the sum of the day's buckets.
        plant(day.plusHours(1), "registrations", "", 3);
        plant(day.plusHours(2), "registrations", "", 4);

        ResponseEntity<Map> response =
                getWithAuth(
                        "/api/v1/admin/stats/timeseries?metric=registrations&from="
                                + iso(now.minusDays(120))
                                + "&to="
                                + iso(now),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The server states the width it used, so a chart does not have to assume one.
        assertThat(dataOf(response).get("granularity")).isEqualTo("day");
        List<Map<String, Object>> points = pointsOf(response);
        assertThat(points).hasSize(1);
        assertThat(((Number) points.get(0).get("value")).longValue()).isEqualTo(7L);
    }

    @Test
    void timeseries_requestedDayGranularity_isHonouredInsideTheHalfHourWindow() {
        // The response has always carried a granularity field, so a client reasonably builds a
        // Half hour / Day toggle and sends one. Accepting the parameter and ignoring it made that
        // toggle do nothing while still returning 200.
        TestUser admin = createUser("stats_reqday_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime yesterday = now.minusDays(1).truncatedTo(ChronoUnit.DAYS);
        plant(yesterday.plusHours(3), "registrations", "", 5);

        ResponseEntity<Map> response =
                getWithAuth(
                        "/api/v1/admin/stats/timeseries?metric=registrations&granularity=day&from="
                                + iso(now.minusDays(2))
                                + "&to="
                                + iso(now),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("granularity")).isEqualTo("day");
        assertThat(pointsOf(response)).hasSize(1);
    }

    @Test
    void timeseries_requestedHalfHourGranularity_isHonouredInsideTheHorizon() {
        TestUser admin = createUser("stats_reqfine_admin", "admin");
        OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        plant(recent, "registrations", "", 11);

        ResponseEntity<Map> response =
                getWithAuth(
                        "/api/v1/admin/stats/timeseries?metric=registrations"
                                + "&granularity=half_hour",
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("granularity")).isEqualTo("half_hour");
        assertThat(pointsOf(response)).hasSize(1);
    }

    @Test
    void timeseries_halfHourRequestedBeyondTheHorizon_isRejectedWithTheHorizonInTheMessage() {
        // Answering at day width would contradict the request, and the response's granularity field
        // would then disagree with what was asked for.
        TestUser admin = createUser("stats_reqfine_old_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ResponseEntity<Map> response =
                getWithAuth(
                        "/api/v1/admin/stats/timeseries?metric=registrations"
                                + "&granularity=half_hour&from="
                                + iso(now.minusDays(120))
                                + "&to="
                                + iso(now),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody()))
                .contains(
                        "Half-hour points are served for windows starting within the last 30 days");
    }

    @Test
    void timeseries_unknownGranularity_isRejected() {
        TestUser admin = createUser("stats_badgran_admin", "admin");

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/stats/timeseries?metric=registrations"
                                                + "&granularity=weekly",
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void timeseries_undeclaredQueryParameter_isRejected() {
        // A mistyped filter that returns an unfiltered 200 is the worst possible answer: the
        // client shows the result as if the filter had been applied.
        TestUser admin = createUser("stats_bogus_admin", "admin");

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/stats/timeseries?metric=registrations"
                                                + "&bogus=1",
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unauthenticatedRequest_isRejectedOnEveryStatisticsRead() {
        assertThat(rest.getForEntity("/api/v1/admin/stats/current", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.getForEntity(
                                        "/api/v1/admin/stats/timeseries?metric=registrations",
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void timeseries_unknownMetric_isRejected() {
        TestUser admin = createUser("stats_badmetric_admin", "admin");

        assertThat(
                        getWithAuth("/api/v1/admin/stats/timeseries?metric=not_a_metric", admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void timeseries_onlyOneBound_isRejectedRatherThanDefaultingTheOther() {
        TestUser admin = createUser("stats_onebound_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/stats/timeseries?metric=registrations&from="
                                                + iso(now.minusDays(1)),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        getWithAuth(
                                        "/api/v1/admin/stats/timeseries?metric=registrations&to="
                                                + iso(now),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void timeseries_reversedOrOverlongWindow_isRejected() {
        TestUser admin = createUser("stats_badwindow_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(seriesStatus(admin, now, now)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(seriesStatus(admin, now, now.minusDays(1))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(seriesStatus(admin, now.minusDays(366), now)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(seriesStatus(admin, now.minusDays(365), now)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void bothReads_analyticsDown_answerTheTypedServiceUnavailableForTheWholeResponse() {
        TestUser admin = createUser("stats_down_admin", "admin");
        insertHashtag("loud", 90);
        plant(OffsetDateTime.parse("2026-08-01T00:00:00Z"), "users_total", "", 10);

        clickhouse.getDockerClient().pauseContainerCmd(clickhouse.getContainerId()).exec();
        ResponseEntity<Map> current = getWithAuth("/api/v1/admin/stats/current", admin);
        ResponseEntity<Map> series =
                getWithAuth("/api/v1/admin/stats/timeseries?metric=registrations", admin);

        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(String.valueOf(current.getBody())).contains("ANALYTICS_UNAVAILABLE");
        // The live hashtag list is not served alongside a missing snapshot.
        assertThat(current.getBody().get("data")).isNull();
        assertThat(series.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(String.valueOf(series.getBody())).contains("ANALYTICS_UNAVAILABLE");
    }

    private HttpStatus seriesStatus(TestUser admin, OffsetDateTime from, OffsetDateTime to) {
        return (HttpStatus)
                getWithAuth(
                                "/api/v1/admin/stats/timeseries?metric=registrations&from="
                                        + iso(from)
                                        + "&to="
                                        + iso(to),
                                admin)
                        .getStatusCode();
    }

    private static String iso(OffsetDateTime time) {
        return ISO.format(time).replace("+", "%2B");
    }

    private TestUser createUser(String username, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local",
                role);
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private void insertHashtag(String name, int postCount) {
        insertHashtag(name, postCount, "active");
    }

    private void insertHashtag(String name, int postCount, String status) {
        jdbcTemplate.update(
                "INSERT INTO hashtags (name, post_count, status)"
                        + " VALUES (?, ?, CAST(? AS hashtag_status))",
                name,
                postCount,
                status);
    }

    // Plants one row the way the ingest consumer would have written it, as the administrator user
    // because the tests need to write and read without the application's pools in between.
    private void plant(OffsetDateTime bucketStart, String metricKey, String dimension, long value) {
        clickHouse(
                "INSERT INTO luvax_analytics.platform_stats"
                        + " (bucket_start, metric_key, dimension, value, computed_at) VALUES ('"
                        + CLICKHOUSE_TIME.format(bucketStart)
                        + "', '"
                        + metricKey
                        + "', '"
                        + dimension
                        + "', "
                        + value
                        + ", now64(6))");
    }

    private static void clickHouse(String sql) {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<Map> getWithAuth(String path, TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pointsOf(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) dataOf(response).get("points");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> topHashtagsOf(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) dataOf(response).get("topHashtags");
    }
}
