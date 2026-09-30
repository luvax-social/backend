package com.app.common.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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

import com.app.common.analytics.ClickHouseUnavailableException.Reason;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Every ClickHouse call through the real pools, users, profiles and the breaker configured in
 * {@code resilience4j-dev.yml}.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "management.health.elasticsearch.enabled=false"
        })
@Testcontainers
class ClickHouseOperationsIT {

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
        r.add("JWT_SECRET", () -> "clickhouse-operations-it-secret-32-min!!");
        r.add("JWT_ISSUER", () -> "https://clickhouse-operations.it.local");
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
    }

    @Autowired private ClickHouseOperations operations;
    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private CircuitBreakerRegistry breakers;

    private CircuitBreaker breaker() {
        return breakers.circuitBreaker(
                com.app.common.analytics.impl.ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME);
    }

    @BeforeEach
    void ready() {
        // Applies the migrations now if the startup attempt has not finished yet.
        assertThat(gate.attempt()).isTrue();
        breaker().reset();
    }

    @AfterEach
    void closeBreaker() {
        breaker().reset();
    }

    @Test
    void migrations_applyAtStartup_soTheGateIsReadyAndCallsAreServed() {
        assertThat(operations.isReady()).isTrue();

        long tables =
                operations.read(
                        "test.tables",
                        client ->
                                client.sql(
                                                "SELECT count() FROM system.tables WHERE database ="
                                                        + " 'luvax_analytics' AND name IN"
                                                        + " ('user_events', 'admin_actions',"
                                                        + " 'platform_stats')")
                                        .query(Long.class)
                                        .single());

        assertThat(tables).isEqualTo(3);
    }

    @Test
    void pools_connectAsTheirOwnUsersUnderTheirOwnProfiles() {
        String readerUser = operations.read("test.user", c -> currentUser(c));
        String batchUser = operations.readBatch("test.user", c -> currentUser(c));
        String readerLimit = operations.read("test.limit", c -> setting(c, "max_execution_time"));
        assertThat(readerUser).isEqualTo("luvax_analytics_reader");
        assertThat(batchUser).isEqualTo("luvax_analytics_reader");
        assertThat(readerLimit).isEqualTo("20.0");

        String[] writer = new String[2];
        operations.write(
                "test.user",
                c -> {
                    writer[0] = currentUser(c);
                    writer[1] = setting(c, "async_insert_busy_timeout_max_ms");
                });
        assertThat(writer[0]).isEqualTo("luvax_analytics_writer");
        assertThat(writer[1]).isEqualTo("20");
    }

    private static String currentUser(org.springframework.jdbc.core.simple.JdbcClient client) {
        return client.sql("SELECT currentUser()").query(String.class).single();
    }

    private static String setting(
            org.springframework.jdbc.core.simple.JdbcClient client, String name) {
        return client.sql("SELECT getSetting('" + name + "')").query(String.class).single();
    }

    @Test
    void writeThenRead_typedRowWithNulls_roundTrips() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime createdAt =
                OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 123_456_000, ZoneOffset.UTC);

        operations.write(
                "test.insert",
                client ->
                        client.sql(
                                        "INSERT INTO user_events (id, user_id, event_type,"
                                                + " entity_type, entity_id, metadata, created_at)"
                                                + " SETTINGS async_insert = 1,"
                                                + " wait_for_async_insert = 1 VALUES (:id,"
                                                + " :userId, :eventType, :entityType, :entityId,"
                                                + " :metadata, :createdAt)")
                                .param("id", id)
                                .param("userId", userId)
                                .param("eventType", "session_start")
                                .param("entityType", null)
                                .param("entityId", null)
                                .param("metadata", "")
                                .param("createdAt", createdAt)
                                .update());

        UserEventProbe row =
                operations.read(
                        "test.select",
                        client ->
                                client.sql(
                                                "SELECT id, user_id, event_type, entity_type,"
                                                        + " entity_id, created_at FROM user_events"
                                                        + " FINAL WHERE id = :id")
                                        .param("id", id)
                                        .query(
                                                (rs, n) ->
                                                        new UserEventProbe(
                                                                rs.getObject("id", UUID.class),
                                                                rs.getObject("user_id", UUID.class),
                                                                rs.getString("event_type"),
                                                                rs.getString("entity_type"),
                                                                rs.getObject(
                                                                        "entity_id", UUID.class),
                                                                rs.getObject(
                                                                        "created_at",
                                                                        OffsetDateTime.class)))
                                        .single());

        assertThat(row.id()).isEqualTo(id);
        assertThat(row.userId()).isEqualTo(userId);
        assertThat(row.eventType()).isEqualTo("session_start");
        assertThat(row.entityType()).isNull();
        assertThat(row.entityId()).isNull();
        assertThat(row.createdAt().toInstant()).isEqualTo(createdAt.toInstant());
    }

    private record UserEventProbe(
            UUID id,
            UUID userId,
            String eventType,
            String entityType,
            UUID entityId,
            OffsetDateTime createdAt) {}

    @Test
    void write_unknownEnumElement_isRejectedAndLeavesTheBreakerUntouched() {
        assertThatThrownBy(
                        () ->
                                operations.write(
                                        "test.bad-enum",
                                        client ->
                                                client.sql(
                                                                "INSERT INTO user_events (user_id,"
                                                                        + " event_type, created_at)"
                                                                        + " SETTINGS async_insert = 1,"
                                                                        + " wait_for_async_insert = 1"
                                                                        + " VALUES (:userId,"
                                                                        + " 'not_a_real_type',"
                                                                        + " :createdAt)")
                                                        .param("userId", UUID.randomUUID())
                                                        .param(
                                                                "createdAt",
                                                                OffsetDateTime.now(ZoneOffset.UTC))
                                                        .update()))
                .isInstanceOfSatisfying(
                        ClickHouseRequestRejectedException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(691));

        assertThat(breaker().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void read_selectingWithoutPrivilege_isUnavailableBecauseAWrongGrantMustPauseNotDeadLetter() {
        // The writer holds INSERT only, so a SELECT through its pool is a privilege failure (497).
        assertThatThrownBy(
                        () ->
                                operations.write(
                                        "test.privilege",
                                        client ->
                                                client.sql("SELECT count() FROM user_events")
                                                        .query(Long.class)
                                                        .single()))
                .isInstanceOf(ClickHouseUnavailableException.class);
    }

    @Test
    void read_tenAvailabilityFailures_openTheBreakerThenCallsAreRefusedWithoutReachingTheServer() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(
                            () ->
                                    operations.read(
                                            "test.missing-table",
                                            client ->
                                                    client.sql("SELECT 1 FROM no_such_table")
                                                            .query(Long.class)
                                                            .single()))
                    .isInstanceOf(ClickHouseUnavailableException.class);
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> operations.read("test.refused", client -> "never"))
                .isInstanceOfSatisfying(
                        ClickHouseUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CIRCUIT_OPEN));
    }
}
