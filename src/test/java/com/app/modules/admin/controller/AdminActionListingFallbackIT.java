package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.admin.repository.AdminActionAnalyticsRepository;
import com.app.modules.admin.repository.AdminActionRepository;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The audit log keeps answering while ClickHouse is down: the same page comes from PostgreSQL, a
 * cursor minted by ClickHouse continues there without skipping or repeating a row, the fallback is
 * counted, and a moderator still sees only its own rows.
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
            // what turns the hang into a failure the fallback can react to.
            "app.analytics.clickhouse.reader.socket-timeout=PT1S",
            "app.analytics.clickhouse.connection-timeout=PT1S"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class AdminActionListingFallbackIT {

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
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "admin-listing-fallback-it-secret-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://admin-listing.it.local");
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
        r.add("app.outbox.publisher.enabled", () -> false);
        r.add("app.hashtag.seed.enabled", () -> false);
        r.add("app.post.seed.enabled", () -> false);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private AdminActionRepository adminActionRepository;
    @Autowired private AdminActionAnalyticsRepository analyticsRepository;

    private record TestUser(UUID id, String token) {}

    @BeforeEach
    void startFromAHealthyReplica() {
        assertThat(gate.attempt()).isTrue();
        breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME).reset();
    }

    @AfterEach
    void restoreClickHouse() {
        try {
            clickhouse.getDockerClient().unpauseContainerCmd(clickhouse.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // The container is not paused when a test failed before pausing it.
        }
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM users");
        // The replica is emptied too, or one test's rows would appear in the next test's listing
        // while PostgreSQL, which the fallback reads, no longer has them.
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement()) {
            statement.execute("TRUNCATE TABLE luvax_analytics.admin_actions");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getActions_clickHouseDown_servesTheSamePageFromPostgresAndACursorContinuesThere() {
        TestUser admin = createUser("fallback_admin", "admin");
        TestUser target = createUser("fallback_target", "user");
        List<UUID> newestFirst = new ArrayList<>();
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        for (int i = 0; i < 5; i++) {
            newestFirst.add(0, seedAction(admin.id(), target.id(), base.plusMinutes(i)));
        }

        ResponseEntity<Map> fromClickHouse = get("/api/v1/admin/actions?limit=2", admin);
        assertThat(fromClickHouse.getStatusCode())
                .as(String.valueOf(fromClickHouse.getBody()))
                .isEqualTo(HttpStatus.OK);
        double fallbacksBefore = fallbackCount();
        Map<?, ?> firstPage = data(fromClickHouse);
        String cursorMintedByClickHouse = endCursor(fromClickHouse);

        pauseClickHouse();
        ResponseEntity<Map> fromPostgres = get("/api/v1/admin/actions?limit=2", admin);
        ResponseEntity<Map> secondPage =
                get("/api/v1/admin/actions?limit=2&cursor=" + cursorMintedByClickHouse, admin);
        assertThat(secondPage.getStatusCode())
                .as(String.valueOf(secondPage.getBody()))
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> thirdPage =
                get("/api/v1/admin/actions?limit=2&cursor=" + endCursor(secondPage), admin);
        assertThat(thirdPage.getStatusCode())
                .as(String.valueOf(thirdPage.getBody()))
                .isEqualTo(HttpStatus.OK);

        assertThat(fromPostgres.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(data(fromPostgres).get("degraded")).isEqualTo(false);
        assertThat(ids(fromPostgres)).isEqualTo(ids(firstPage));
        assertThat(fallbackCount()).isGreaterThan(fallbacksBefore);
        List<String> walked = new ArrayList<>(ids(firstPage));
        walked.addAll(ids(secondPage));
        walked.addAll(ids(thirdPage));
        assertThat(walked)
                .containsExactlyElementsOf(newestFirst.stream().map(UUID::toString).toList());
    }

    @Test
    void getActions_clickHouseDown_aModeratorStillSeesOnlyItsOwnRows() {
        TestUser admin = createUser("scope_admin", "admin");
        TestUser moderator = createUser("scope_mod", "moderator");
        TestUser target = createUser("scope_target", "user");
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        seedAction(admin.id(), target.id(), base);
        UUID own = seedAction(moderator.id(), target.id(), base.plusMinutes(1));
        seedAction(admin.id(), target.id(), base.plusMinutes(2));

        pauseClickHouse();
        ResponseEntity<Map> response =
                get("/api/v1/admin/actions?adminId=" + admin.id(), moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response)).containsExactly(own.toString());
    }

    /** Writes one audit row to PostgreSQL and replicates it, the way the consumer would. */
    private UUID seedAction(UUID adminId, UUID targetUserId, OffsetDateTime createdAt) {
        UUID id =
                jdbcTemplate.queryForObject(
                        "INSERT INTO admin_actions(admin_id, action_type, target_user_id,"
                                + " created_at) VALUES (?, 'ban_user', ?, ?) RETURNING id",
                        UUID.class,
                        adminId,
                        targetUserId,
                        createdAt);
        analyticsRepository.insert(adminActionRepository.findReplicaRow(id).orElseThrow());
        return id;
    }

    private void pauseClickHouse() {
        clickhouse.getDockerClient().pauseContainerCmd(clickhouse.getContainerId()).exec();
    }

    private double fallbackCount() {
        return meterRegistry.counter("luvax.analytics.audit_log.fallback").count();
    }

    private ResponseEntity<Map> get(String path, TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static Map<?, ?> data(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(Map<?, ?> data) {
        return ((List<Map<?, ?>>) data.get("content"))
                .stream().map(row -> row.get("id").toString()).toList();
    }

    private static List<String> ids(ResponseEntity<Map> response) {
        return ids(data(response));
    }

    private static String endCursor(ResponseEntity<Map> response) {
        return (String) ((Map<?, ?>) data(response).get("pageInfo")).get("endCursor");
    }

    private TestUser createUser(String prefix, String role) {
        UUID id = UUID.randomUUID();
        String username = prefix + "_" + id.toString().substring(0, 4);
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local",
                role);
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }
}
