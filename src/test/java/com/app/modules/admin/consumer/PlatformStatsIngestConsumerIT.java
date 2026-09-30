package com.app.modules.admin.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent.Row;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Statistics ingestion end to end: a collected-bucket event on the bus becomes rows in ClickHouse,
 * a re-collection of the same bucket converges on the later computation, and a message that can
 * never succeed goes to the dead-letter queue instead of stalling ingestion.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.stats.enabled=false"
        })
@Testcontainers
@Import(PlatformStatsIngestConsumerIT.InboxProbeConfig.class)
class PlatformStatsIngestConsumerIT {

    private static final Instant BUCKET = Instant.parse("2026-07-01T10:00:00Z");

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
        r.add("JWT_SECRET", () -> "platform-stats-ingest-it-secret-32-chars-min!");
        r.add("JWT_ISSUER", () -> "https://platform-stats-ingest.it.local");
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

    @BeforeEach
    void startIngestion() {
        assertThat(gate.attempt()).isTrue();
        // Releasing a suspension nobody holds reconciles the listener containers synchronously, so
        // the consumer is running before the test publishes, without waiting on the gate's event.
        controller.resume("test");
        assertThat(controller.isRunning(AnalyticsListenerIds.PLATFORM_STATS_INGEST)).isTrue();
        breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME).reset();
    }

    @AfterEach
    void emptyTheStore() throws Exception {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement()) {
            statement.execute("TRUNCATE TABLE luvax_analytics.platform_stats");
        }
    }

    @Test
    void collectedEvent_landsEveryRowOfTheBucket() throws Exception {
        awaitProcessed(
                publish(
                        BUCKET,
                        BUCKET.plusSeconds(1900),
                        List.of(
                                new Row("users_total", "", 40),
                                new Row("users_by_status", "active", 38),
                                new Row("users_by_status", "banned", 2),
                                new Row("registrations", "", 3))));

        Map<String, Long> rows = storedRows(BUCKET);
        assertThat(rows)
                .containsEntry("users_total|", 40L)
                .containsEntry("users_by_status|active", 38L)
                .containsEntry("users_by_status|banned", 2L)
                .containsEntry("registrations|", 3L)
                .hasSize(4);
    }

    @Test
    void aRedeliveredCopyAddsNothing_andALaterRecollectionReplacesTheEarlierValues()
            throws Exception {
        Instant first = BUCKET.plusSeconds(1900);
        awaitProcessed(publish(BUCKET, first, List.of(new Row("posts_total", "", 5))));
        // Same computation delivered again under a new event id, as a broker redelivery after a
        // consumer restart would look to the inbox.
        awaitProcessed(publish(BUCKET, first, List.of(new Row("posts_total", "", 5))));
        assertThat(storedRows(BUCKET)).containsOnly(Map.entry("posts_total|", 5L));

        // The bucket collected again later, with a different figure.
        awaitProcessed(
                publish(BUCKET, first.plusSeconds(600), List.of(new Row("posts_total", "", 8))));

        assertThat(storedRows(BUCKET)).containsOnly(Map.entry("posts_total|", 8L));
    }

    @Test
    void malformedEvent_deadLettersAtOnce() {
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                new Message("not an envelope".getBytes(StandardCharsets.UTF_8)));

        Message deadLettered =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.PLATFORM_STATS_DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
    }

    @Test
    void eventWithoutRows_deadLettersAtOnce() {
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        UUID.randomUUID(),
                        AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        PlatformStatsCollectedEvent.AGGREGATE_TYPE,
                        PlatformStatsCollectedEvent.aggregateId(BUCKET),
                        Map.of("bucketStart", BUCKET.toString(), "computedAt", BUCKET.toString()));
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));

        Message deadLettered =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.PLATFORM_STATS_DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
    }

    private UUID publish(Instant bucketStart, Instant computedAt, List<Row> rows) {
        UUID eventId = UUID.randomUUID();
        processedEvents.put(eventId, new CompletableFuture<>());
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        PlatformStatsCollectedEvent.AGGREGATE_TYPE,
                        PlatformStatsCollectedEvent.aggregateId(bucketStart),
                        PlatformStatsCollectedEvent.payload(bucketStart, 1800, computedAt, rows));
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));
        return eventId;
    }

    private void awaitProcessed(UUID eventId) throws Exception {
        processedEvents
                .computeIfAbsent(eventId, id -> new CompletableFuture<>())
                .get(60, TimeUnit.SECONDS);
    }

    // Keyed "metric|dimension" so a test reads one figure without walking the result set.
    private Map<String, Long> storedRows(Instant bucketStart) throws SQLException {
        Map<String, Long> rows = new LinkedHashMap<>();
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT metric_key, dimension, value FROM"
                                        + " luvax_analytics.platform_stats FINAL WHERE bucket_start"
                                        + " = '"
                                        + java.time.format.DateTimeFormatter.ofPattern(
                                                        "yyyy-MM-dd HH:mm:ss")
                                                .withZone(ZoneOffset.UTC)
                                                .format(bucketStart)
                                        + "'")) {
            while (result.next()) {
                rows.put(
                        result.getString("metric_key") + "|" + result.getString("dimension"),
                        result.getLong("value"));
            }
        }
        return rows;
    }
}
