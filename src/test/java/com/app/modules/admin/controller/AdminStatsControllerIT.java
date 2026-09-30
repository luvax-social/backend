package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class AdminStatsControllerIT {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

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

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM platform_stats");
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
        plantFine(bucket, "users_total", "", 999_999);
        plantFine(bucket, "users_by_status", "active", 999_998);
        plantFine(bucket, "posts_total", "", 4242);

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
        plantFine(OffsetDateTime.parse("2026-08-01T00:00:00Z"), "users_total", "", 100);
        plantFine(OffsetDateTime.parse("2026-08-01T00:30:00Z"), "users_total", "", 200);

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
    void timeseries_noBounds_defaultsToTwentyFourHoursOfFineBuckets() {
        TestUser admin = createUser("stats_default_admin", "admin");
        OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        plantFine(recent, "registrations", "", 11);

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
    void timeseries_windowOlderThanFineRetention_isServedFromDailyRows() {
        TestUser admin = createUser("stats_daily_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        plantDaily(now.minusDays(100).truncatedTo(java.time.temporal.ChronoUnit.DAYS), 7);

        ResponseEntity<Map> response =
                getWithAuth(
                        "/api/v1/admin/stats/timeseries?metric=registrations&from="
                                + iso(now.minusDays(120))
                                + "&to="
                                + iso(now),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The server states the width it used, so a chart does not have to assume one. Fine buckets
        // do not survive that far back, so answering at half_hour would return an empty series and
        // look like nothing happened.
        assertThat(dataOf(response).get("granularity")).isEqualTo("day");
        assertThat(pointsOf(response)).hasSize(1);
    }

    @Test
    void timeseries_requestedDayGranularity_isHonouredInsideTheFineWindow() {
        // The response has always carried a granularity field, so a client reasonably builds a
        // Half hour / Day toggle and sends one. Accepting the parameter and ignoring it made that
        // toggle do nothing while still returning 200.
        TestUser admin = createUser("stats_reqday_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        plantDaily(now.minusDays(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS), 5);

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
    void timeseries_requestedHalfHourGranularity_isHonouredInsideTheFineWindow() {
        TestUser admin = createUser("stats_reqfine_admin", "admin");
        OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        plantFine(recent, "registrations", "", 11);

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
    void timeseries_halfHourRequestedBeyondFineRetention_isRejectedNotAnsweredEmpty() {
        // Those rows were rolled up and deleted. An empty series would be indistinguishable from a
        // stretch in which nothing happened, and the response's granularity field would contradict
        // what was asked for.
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

    private void plantFine(
            OffsetDateTime bucketStart, String metricKey, String dimension, long value) {
        jdbcTemplate.update(
                "INSERT INTO platform_stats"
                        + " (bucket_start, granularity, metric_key, dimension, value)"
                        + " VALUES (?, CAST('half_hour' AS stat_granularity), ?, ?, ?)",
                bucketStart,
                metricKey,
                dimension,
                value);
    }

    private void plantDaily(OffsetDateTime bucketStart, long value) {
        jdbcTemplate.update(
                "INSERT INTO platform_stats"
                        + " (bucket_start, granularity, metric_key, dimension, value)"
                        + " VALUES (?, CAST('day' AS stat_granularity), 'registrations', '', ?)",
                bucketStart,
                value);
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
