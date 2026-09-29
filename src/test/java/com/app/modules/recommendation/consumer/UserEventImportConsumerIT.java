package com.app.modules.recommendation.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import com.app.modules.recommendation.enums.UserEventType;
import com.app.modules.recommendation.messaging.RecommendationEventTypes;
import com.app.modules.recommendation.messaging.UserEventImportedEvent;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Imported behavioural events end to end: an event on the bus becomes a row in ClickHouse with its
 * own time and no Gorse feedback, a redelivery adds nothing, and a message that can never succeed
 * goes to the dead-letter queue instead of stalling ingestion.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
@Import(UserEventImportConsumerIT.InboxProbeConfig.class)
class UserEventImportConsumerIT {

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
        r.add("JWT_SECRET", () -> "user-event-import-it-secret-32-characters!!");
        r.add("JWT_ISSUER", () -> "https://user-event-import.it.local");
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

    @Autowired
    private ConcurrentMap<UUID, CompletableFuture<ProcessedMessageResult>> processedEvents;

    private final JdbcClient clickHouse =
            ClickHouseTestSupport.clientAs(
                    clickhouse, clickhouse.getUsername(), clickhouse.getPassword());

    @BeforeEach
    void startIngestion() {
        assertThat(gate.attempt()).isTrue();
        // Releasing a suspension nobody holds reconciles the listener containers synchronously, so
        // the consumer is running before the test publishes, without waiting on the gate's event.
        controller.resume("test");
        assertThat(controller.isRunning(AnalyticsListenerIds.USER_EVENT_IMPORT)).isTrue();
        breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME).reset();
        clickHouse.sql("TRUNCATE TABLE user_events").update();
    }

    @Test
    void importedEvent_landsWithItsOwnTimeAndNoGorseFeedback() throws Exception {
        UUID user = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-15T09:30:00.123456Z");

        UUID eventId =
                publish(
                        UserEventImportedEvent.payload(
                                UserEventType.PROFILE_VIEW, "user", target, null, createdAt),
                        user);
        awaitProcessed(eventId);

        Stored stored = stored(eventId);
        assertThat(stored.userId()).isEqualTo(user);
        assertThat(stored.eventType()).isEqualTo("profile_view");
        assertThat(stored.entityType()).isEqualTo("user");
        assertThat(stored.entityId()).isEqualTo(target);
        assertThat(stored.createdAt().toInstant()).isEqualTo(createdAt);
        assertThat(stored.feedbackType()).isNull();
    }

    @Test
    void searchMetadata_isStoredAsTheTextItArrivedAs() throws Exception {
        UUID user = UUID.randomUUID();
        String metadata = "{\"scope\":\"users\",\"query\":\"anna\"}";

        UUID eventId =
                publish(
                        UserEventImportedEvent.payload(
                                UserEventType.SEARCH,
                                null,
                                null,
                                metadata,
                                Instant.parse("2026-08-15T09:30:00Z")),
                        user);
        awaitProcessed(eventId);

        assertThat(stored(eventId).metadata()).isEqualTo(metadata);
    }

    @Test
    void redelivery_doesNotAddARowUnderFinal() throws Exception {
        UUID user = UUID.randomUUID();
        Map<String, Object> data =
                UserEventImportedEvent.payload(
                        UserEventType.SESSION_START,
                        null,
                        null,
                        null,
                        Instant.parse("2026-08-15T09:30:00Z"));
        UUID eventId = UUID.randomUUID();
        processedEvents.put(eventId, new CompletableFuture<>());
        send(eventId, data, user);
        awaitProcessed(eventId);

        // The same event delivered again: the inbox skips the handler, and the row that is already
        // there is the only one.
        processedEvents.put(eventId, new CompletableFuture<>());
        send(eventId, data, user);
        awaitProcessed(eventId);

        Long rows =
                clickHouse
                        .sql("SELECT count() FROM user_events FINAL WHERE id = ?")
                        .param(eventId)
                        .query(Long.class)
                        .single();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void malformedEvent_deadLettersAtOnce() {
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                RecommendationEventTypes.USER_EVENT_IMPORTED_V1,
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8)));

        Message deadLettered =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.USER_EVENT_IMPORT_DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
    }

    @Test
    void eventWithAnUnknownEventType_deadLettersAtOnce() {
        send(
                UUID.randomUUID(),
                Map.of("eventType", "not_a_type", "createdAt", "2026-08-15T09:30:00Z"),
                UUID.randomUUID());

        Message deadLettered =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.USER_EVENT_IMPORT_DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
    }

    @Test
    void eventWithoutATimestamp_deadLettersAtOnce() {
        send(UUID.randomUUID(), Map.of("eventType", "search"), UUID.randomUUID());

        Message deadLettered =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.USER_EVENT_IMPORT_DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
    }

    private UUID publish(Map<String, Object> data, UUID userId) {
        UUID eventId = UUID.randomUUID();
        processedEvents.put(eventId, new CompletableFuture<>());
        send(eventId, data, userId);
        return eventId;
    }

    private void send(UUID eventId, Map<String, Object> data, UUID userId) {
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        RecommendationEventTypes.USER_EVENT_IMPORTED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        userId,
                        UserEventImportedEvent.AGGREGATE_TYPE,
                        userId,
                        data);
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                RecommendationEventTypes.USER_EVENT_IMPORTED_V1,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));
    }

    private void awaitProcessed(UUID eventId) throws Exception {
        processedEvents
                .computeIfAbsent(eventId, id -> new CompletableFuture<>())
                .get(60, TimeUnit.SECONDS);
    }

    private Stored stored(UUID eventId) {
        return clickHouse
                .sql(
                        "SELECT user_id, toString(event_type) AS event_type, entity_type,"
                                + " entity_id, metadata, created_at, feedback_type FROM"
                                + " user_events FINAL WHERE id = ?")
                .param(eventId)
                .query(
                        (rows, rowNumber) ->
                                new Stored(
                                        rows.getObject("user_id", UUID.class),
                                        rows.getString("event_type"),
                                        rows.getString("entity_type"),
                                        rows.getObject("entity_id", UUID.class),
                                        rows.getString("metadata"),
                                        rows.getObject("created_at", OffsetDateTime.class),
                                        rows.getString("feedback_type")))
                .single();
    }

    private record Stored(
            UUID userId,
            String eventType,
            String entityType,
            UUID entityId,
            String metadata,
            OffsetDateTime createdAt,
            String feedbackType) {}
}
