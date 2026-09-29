package com.app.modules.admin.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.ingest.AnalyticsIngestionController;
import com.app.common.analytics.ingest.AnalyticsListenerIds;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Replication of the audit log end to end: an event on the bus becomes a row in ClickHouse, a
 * cascade's rewrite converges on the newest version whatever order events arrive in, and a message
 * that can never succeed goes to the dead-letter queue instead of stalling ingestion.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
@Import(AdminActionReplicationConsumerIT.InboxProbeConfig.class)
class AdminActionReplicationConsumerIT {

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        ClickHouseTestSupport.register(r, clickhouse);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> "admin-replication-it-secret-32-characters!!");
        r.add("JWT_ISSUER", () -> "https://admin-replication.it.local");
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

    /**
     * Completes a future per event id once the consumer's inbox call has returned, which is after
     * the handler wrote to ClickHouse and its transaction committed. It lets a test wait for the
     * consumer to finish an event without polling either store.
     */
    @TestConfiguration
    static class InboxProbeConfig {

        @Bean
        ConcurrentMap<UUID, CompletableFuture<ProcessedMessageResult>> processedEvents() {
            return new ConcurrentHashMap<>();
        }

        @Bean
        @Primary
        ProcessedMessageService probingProcessedMessageService(
                @Qualifier("processedMessageServiceImpl") ProcessedMessageService delegate,
                ConcurrentMap<UUID, CompletableFuture<ProcessedMessageResult>> processedEvents) {
            return (consumerName, eventId, eventType, handler) -> {
                CompletableFuture<ProcessedMessageResult> done =
                        processedEvents.computeIfAbsent(eventId, id -> new CompletableFuture<>());
                try {
                    ProcessedMessageResult result =
                            delegate.processOnce(consumerName, eventId, eventType, handler);
                    done.complete(result);
                    return result;
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                    throw e;
                }
            };
        }
    }

    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private AnalyticsIngestionController controller;
    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private JdbcClient jdbcClient;

    @Autowired
    private ConcurrentMap<UUID, CompletableFuture<ProcessedMessageResult>> processedEvents;

    @BeforeEach
    void startIngestion() {
        assertThat(gate.attempt()).isTrue();
        // Releasing a suspension nobody holds reconciles the listener containers synchronously, so
        // the consumer is running before the test publishes, without waiting on the gate's event.
        controller.resume("test");
        assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
        breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME).reset();
    }

    @Test
    void recordedEvent_replicatesTheRowAtVersionOne() throws Exception {
        UUID admin = insertUser("replication_recorded_admin");
        UUID target = insertUser("replication_recorded_target");
        UUID actionId =
                insertAdminAction(admin, target, "remove_post", "spam", "{\"postId\": \"x\"}");

        awaitProcessed(publish(AdminEventTypes.ACTION_RECORDED_V1, actionId));

        ReplicaRow row = replicaRow(actionId);
        assertThat(row.count()).isEqualTo(1);
        assertThat(row.adminId()).isEqualTo(admin);
        assertThat(row.actionType()).isEqualTo("remove_post");
        assertThat(row.reason()).isEqualTo("spam");
        assertThat(row.metadata()).contains("postId");
        assertThat(row.rowVersion()).isEqualTo(1L);
        assertThat(row.createdAt()).isEqualTo(postgresCreatedAt(actionId));
    }

    @Test
    void cascadeRewrite_convergesOnTheNewestVersionAndAnOldEventCannotRegressIt() throws Exception {
        UUID admin = insertUser("replication_cascade_admin");
        UUID actionId = insertAdminAction(admin, null, "ban_user", "abuse", null);
        awaitProcessed(publish(AdminEventTypes.ACTION_RECORDED_V1, actionId));
        assertThat(replicaRow(actionId).adminId()).isEqualTo(admin);

        // Deleting the acting administrator is the documented recovery for a rogue account. The
        // update trigger writes the change event, and this test forwards it exactly as written.
        jdbcClient.sql("DELETE FROM users WHERE id = :id").param("id", admin).update();
        String triggerPayload =
                jdbcClient
                        .sql(
                                "SELECT payload::text FROM outbox_events WHERE event_type ="
                                        + " 'admin.action.changed.v1' AND aggregate_id = :id")
                        .param("id", actionId)
                        .query(String.class)
                        .single();
        DomainEventEnvelope changed = DomainEventEnvelopeJson.read(triggerPayload);
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AdminEventTypes.ACTION_CHANGED_V1,
                new Message(triggerPayload.getBytes(StandardCharsets.UTF_8)));
        awaitProcessed(changed.eventId());

        ReplicaRow converged = replicaRow(actionId);
        assertThat(converged.adminId()).isNull();
        assertThat(converged.rowVersion()).isEqualTo(2L);

        // The first event, redelivered late, makes the consumer fetch the row again, and the row it
        // fetches is the current one.
        awaitProcessed(publish(AdminEventTypes.ACTION_RECORDED_V1, actionId));

        ReplicaRow afterReplay = replicaRow(actionId);
        assertThat(afterReplay.adminId()).isNull();
        assertThat(afterReplay.rowVersion()).isEqualTo(2L);
    }

    @Test
    void eventForARowThatNoLongerExists_isAcknowledgedWithoutWritingAnything() throws Exception {
        UUID vanished = UUID.randomUUID();

        awaitProcessed(publish(AdminEventTypes.ACTION_RECORDED_V1, vanished));

        assertThat(replicaRow(vanished).count()).isZero();
        assertThat(deadLetterCount()).isZero();
    }

    @Test
    void malformedEvent_deadLettersAtOnce() {
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AdminEventTypes.ACTION_RECORDED_V1,
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8)));

        Message deadLettered =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.ADMIN_ACTION_REPLICATION_DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
    }

    @Test
    void eventWithoutAnActionId_deadLettersAtOnce() {
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        AdminEventTypes.ACTION_RECORDED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        "admin_action",
                        UUID.randomUUID(),
                        Map.of());
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AdminEventTypes.ACTION_RECORDED_V1,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));

        Message deadLettered =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.ADMIN_ACTION_REPLICATION_DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
    }

    private UUID publish(String eventType, UUID actionId) {
        UUID eventId = UUID.randomUUID();
        processedEvents.put(eventId, new CompletableFuture<>());
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        eventType,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        "admin_action",
                        actionId,
                        Map.of("adminActionId", actionId.toString()));
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                eventType,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));
        return eventId;
    }

    private void awaitProcessed(UUID eventId) throws Exception {
        processedEvents
                .computeIfAbsent(eventId, id -> new CompletableFuture<>())
                .get(60, TimeUnit.SECONDS);
    }

    private long deadLetterCount() {
        return rabbitTemplate
                .execute(
                        channel ->
                                channel.queueDeclarePassive(
                                        RabbitMqTopologyConfig
                                                .ADMIN_ACTION_REPLICATION_DEAD_LETTER_QUEUE))
                .getMessageCount();
    }

    private OffsetDateTime postgresCreatedAt(UUID actionId) {
        return jdbcClient
                .sql("SELECT created_at FROM admin_actions WHERE id = :id")
                .param("id", actionId)
                .query(OffsetDateTime.class)
                .single()
                .withOffsetSameInstant(ZoneOffset.UTC);
    }

    private ReplicaRow replicaRow(UUID actionId) throws Exception {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT id, admin_id, action_type, reason, metadata, created_at,"
                                        + " row_version, count() OVER () AS total FROM"
                                        + " luvax_analytics.admin_actions FINAL WHERE id = '"
                                        + actionId
                                        + "'")) {
            if (!rows.next()) {
                return new ReplicaRow(0, null, null, null, null, null, 0);
            }
            return new ReplicaRow(
                    rows.getLong("total"),
                    rows.getObject("admin_id", UUID.class),
                    rows.getString("action_type"),
                    rows.getString("reason"),
                    rows.getString("metadata"),
                    rows.getObject("created_at", OffsetDateTime.class)
                            .withOffsetSameInstant(ZoneOffset.UTC),
                    rows.getLong("row_version"));
        }
    }

    private record ReplicaRow(
            long count,
            UUID adminId,
            String actionType,
            String reason,
            String metadata,
            OffsetDateTime createdAt,
            long rowVersion) {}

    private UUID insertUser(String username) {
        return jdbcClient
                .sql(
                        "INSERT INTO users(username, email, display_name) VALUES (:username,"
                                + " :email, :displayName) RETURNING id")
                .param("username", username)
                .param("email", username + "@example.com")
                .param("displayName", username)
                .query(UUID.class)
                .single();
    }

    private UUID insertAdminAction(
            UUID adminId, UUID targetUserId, String actionType, String reason, String metadata) {
        return jdbcClient
                .sql(
                        "INSERT INTO admin_actions(admin_id, action_type, target_user_id, reason,"
                                + " metadata) VALUES (:adminId, CAST(:actionType AS"
                                + " admin_action_type), :targetUserId, :reason, CAST(:metadata AS"
                                + " jsonb)) RETURNING id")
                .param("adminId", adminId)
                .param("actionType", actionType)
                .param("targetUserId", targetUserId)
                .param("reason", reason)
                .param("metadata", metadata)
                .query(UUID.class)
                .single();
    }
}
