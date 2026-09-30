package com.app.modules.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Proves the three write paths produce exactly the events they claim to, and that an analytics
 * failure never reaches the caller.
 *
 * <p>The rows land in ClickHouse. Every assertion is preceded by {@link
 * UserEventRecorder#awaitQuiescence(Duration)}, which settles by acquiring every in-flight permit
 * rather than by sleeping or polling, and then by flushing ClickHouse's asynchronous insert buffer,
 * because the recorder does not wait for a flush and a row is visible only after one.
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
            // what turns the hang into a drop the recorder can count.
            "app.analytics.clickhouse.writer.socket-timeout=PT1S",
            "app.analytics.clickhouse.connection-timeout=PT1S"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class UserEventRecordingIT {

    private static final Duration SETTLE = Duration.ofSeconds(20);
    private static final String PASSWORD = "SeedPass123!";

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
        // These tests drive login, register and report submission as setup, not as the
        // subject under test. The kill switch keeps them off the network: the dev profile
        // defaults the secret to Cloudflare's test key, and a real siteverify call would
        // make the suite depend on an external service being reachable.
        registry.add("TURNSTILE_AUTH_ENABLED", () -> false);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "user-event-recording-it-secret-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://userevent.it.local");
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
    }

    @TestConfiguration
    static class TracingTestConfig {

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SdkTracerProviderBuilderCustomizer inMemorySpanExporterCustomizer(
                InMemorySpanExporter exporter) {
            return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(exporter));
        }
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserEventRecorder userEventRecorder;
    @Autowired private InMemorySpanExporter spanExporter;
    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private MeterRegistry meterRegistry;

    private final JdbcClient clickHouse =
            ClickHouseTestSupport.clientAs(
                    clickhouse, clickhouse.getUsername(), clickhouse.getPassword());

    private record TestUser(UUID id, String username, String token) {}

    @BeforeEach
    void startFromAHealthyStore() {
        assertThat(gate.attempt()).isTrue();
        breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME).reset();
    }

    @AfterEach
    void cleanup() {
        assertThat(userEventRecorder.awaitQuiescence(SETTLE)).isTrue();
        try {
            clickhouse.getDockerClient().unpauseContainerCmd(clickhouse.getContainerId()).exec();
        } catch (RuntimeException notPaused) {
            // The container is not paused when a test failed before pausing it.
        }
        clickHouse.sql("TRUNCATE TABLE user_events").update();
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM users");
        spanExporter.reset();
    }

    @Test
    void login_successful_writesExactlyOneSessionStart() {
        TestUser user = createUser("evt_login_ok", "user");

        ResponseEntity<Map> response = login(user.username(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        settle();
        assertThat(eventTypesFor(user.id())).containsExactly("session_start");
    }

    @Test
    void login_wrongPassword_writesNothing() {
        TestUser user = createUser("evt_login_bad", "user");

        ResponseEntity<Map> response = login(user.username(), "WrongPassword123!");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        settle();
        assertThat(eventTypesFor(user.id())).isEmpty();
    }

    @Test
    void userSearch_writesExactlyOneSearchEventCarryingTheTerm() {
        TestUser viewer = createUser("evt_search_viewer", "user");
        createUser("evt_search_target", "user");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/search?q=evt_search", viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        settle();
        assertThat(eventTypesFor(viewer.id())).containsExactly("search");
        assertThat(metadataFieldFor(viewer.id(), "scope")).containsExactly("users");
        assertThat(metadataFieldFor(viewer.id(), "query")).containsExactly("evt_search");
    }

    @Test
    void userSearch_queryTooShort_writesNothing() {
        TestUser viewer = createUser("evt_search_short", "user");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/search?q=a", viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        settle();
        assertThat(eventTypesFor(viewer.id())).isEmpty();
    }

    @Test
    void profileRead_ofAnotherAccount_writesProfileViewNamingTheTarget() {
        TestUser viewer = createUser("evt_view_viewer", "user");
        TestUser target = createUser("evt_view_target", "user");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/" + target.id(), viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        settle();
        assertThat(eventTypesFor(viewer.id())).containsExactly("profile_view");
        assertThat(entityIdsFor(viewer.id())).containsExactly(target.id());
    }

    @Test
    void profileRead_ofOwnAccount_writesNothing() {
        TestUser viewer = createUser("evt_view_self", "user");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/" + viewer.id(), viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        settle();
        assertThat(eventTypesFor(viewer.id())).isEmpty();
    }

    @Test
    void recordSearch_insideObservation_insertSpanSharesCallerTrace() {
        TestUser viewer = createUser("evt_trace_viewer", "user");
        createUser("evt_trace_target", "user");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/search?q=evt_trace", viewer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        settle();

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData serverSpan =
                spans.stream()
                        .filter(span -> span.getKind() == SpanKind.SERVER)
                        .filter(span -> span.getName().toLowerCase().contains("search"))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("no server span for the search request"));
        // The JDBC span's own name is just "query"; the SQL text lives in its jdbc.query[0]
        // attribute (net.ttddyy.observation.tracing.JdbcObservation), so the insert is found by
        // attribute value rather than by span name.
        boolean insertSharesTrace =
                spans.stream()
                        .anyMatch(
                                span ->
                                        span.getAttributes().asMap().entrySet().stream()
                                                        .anyMatch(
                                                                entry ->
                                                                        String.valueOf(
                                                                                        entry
                                                                                                .getValue())
                                                                                .contains(
                                                                                        "INSERT INTO user_events"))
                                                && span.getTraceId()
                                                        .equals(serverSpan.getTraceId()));

        assertThat(insertSharesTrace)
                .as("the user_events insert span should share the search request's trace id")
                .isTrue();
    }

    @Test
    void recordedEvent_carriesTheRequestTimeAndAGeneratedId() {
        TestUser viewer = createUser("evt_time_viewer", "user");
        TestUser target = createUser("evt_time_target", "user");
        java.time.Instant before = java.time.Instant.now();

        getWithAuth("/api/v1/users/" + target.id(), viewer);

        settle();
        java.time.Instant after = java.time.Instant.now();
        java.time.OffsetDateTime storedAt =
                clickHouse
                        .sql("SELECT created_at FROM user_events WHERE user_id = ?")
                        .param(viewer.id())
                        .query(java.time.OffsetDateTime.class)
                        .single();
        assertThat(storedAt.toInstant()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        Long distinctIds =
                clickHouse
                        .sql("SELECT uniqExact(id) FROM user_events WHERE user_id = ?")
                        .param(viewer.id())
                        .query(Long.class)
                        .single();
        assertThat(distinctIds).isEqualTo(1L);
    }

    // The analytics store being down is the failure that matters: every request still succeeds,
    // the rows are dropped, and each drop is counted by why, so an operator can tell an open
    // breaker
    // from a fault.
    @Test
    void clickHouseDown_everyRequestStillSucceedsAndTheDropsAreCountedByReason() {
        TestUser viewer = createUser("evt_fail_viewer", "user");
        TestUser target = createUser("evt_fail_target", "user");
        double errorsBefore = dropped("error");
        double openBefore = dropped("circuit_open");

        clickhouse.getDockerClient().pauseContainerCmd(clickhouse.getContainerId()).exec();
        for (int i = 0; i < 6; i++) {
            ResponseEntity<Map> response = getWithAuth("/api/v1/users/" + target.id(), viewer);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        awaitRecorder();
        // The breaker has now seen enough failures to open, so the next request is refused before
        // it reaches the network.
        ResponseEntity<Map> afterOpen = getWithAuth("/api/v1/users/" + target.id(), viewer);
        assertThat(afterOpen.getStatusCode()).isEqualTo(HttpStatus.OK);
        awaitRecorder();

        assertThat(dropped("error") - errorsBefore).isGreaterThanOrEqualTo(1.0);
        assertThat(dropped("circuit_open") - openBefore).isGreaterThanOrEqualTo(1.0);
    }

    private double dropped(String reason) {
        return meterRegistry
                .get("luvax.analytics.user_events.dropped")
                .tag("reason", reason)
                .counter()
                .count();
    }

    private void settle() {
        awaitRecorder();
        // A row the recorder inserted without waiting is visible only after the buffer is flushed.
        clickHouse.sql("SYSTEM FLUSH ASYNC INSERT QUEUE").update();
    }

    // For a test that pauses ClickHouse: nothing can be flushed on a paused server, and the admin
    // connection has no timeout of its own, so a flush there would wait forever.
    private void awaitRecorder() {
        assertThat(userEventRecorder.awaitQuiescence(SETTLE)).isTrue();
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
        jdbcTemplate.update(
                "INSERT INTO user_credentials (user_id, password_hash, email_verified, "
                        + "email_verified_at) VALUES (?, crypt(?, gen_salt('bf', 12)), TRUE, NOW())",
                id,
                PASSWORD);
        return new TestUser(
                id, username, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private ResponseEntity<Map> login(String identifier, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("identifier", identifier, "password", password), headers),
                Map.class);
    }

    private ResponseEntity<Map> getWithAuth(String path, TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private List<String> eventTypesFor(UUID userId) {
        return clickHouse
                .sql(
                        "SELECT toString(event_type) FROM user_events WHERE user_id = ? ORDER BY"
                                + " created_at")
                .param(userId)
                .query(String.class)
                .list();
    }

    private List<String> metadataFieldFor(UUID userId, String field) {
        return clickHouse
                .sql(
                        "SELECT JSONExtractString(metadata, ?) FROM user_events WHERE user_id = ?"
                                + " ORDER BY created_at")
                .param(field)
                .param(userId)
                .query(String.class)
                .list();
    }

    private List<UUID> entityIdsFor(UUID userId) {
        return clickHouse
                .sql("SELECT entity_id FROM user_events WHERE user_id = ? ORDER BY created_at")
                .param(userId)
                .query(UUID.class)
                .list();
    }
}
