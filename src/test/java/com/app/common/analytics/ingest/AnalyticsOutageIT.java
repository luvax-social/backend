package com.app.common.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.recommendation.client.GorseClient;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * A hung ClickHouse pauses ingestion instead of dead-lettering it: the breaker opens, the listener
 * container stops, the messages wait ready in their queue, and after recovery every row arrives
 * exactly once.
 *
 * <p>Driven through two consumers, the admin action replication consumer and the recommendation
 * feedback consumer, with real audit rows and accounts in PostgreSQL and real events on the bus.
 * The recommender is the only stand-in: the feedback consumer writes ClickHouse first and Gorse
 * second, so a paused ClickHouse must never reach it.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.recommendation.consumer.enabled=true",
            // A paused server accepts the connection and never answers, so the socket timeout is
            // what turns the hang into a failure the breaker can count.
            "app.analytics.clickhouse.writer.socket-timeout=PT1S",
            "app.analytics.clickhouse.connection-timeout=PT1S"
        })
@Testcontainers
@Import(AnalyticsOutageIT.InboxProbeConfig.class)
class AnalyticsOutageIT {

    static final String QUEUE = RabbitMqTopologyConfig.ADMIN_ACTION_REPLICATION_QUEUE;
    static final String DEAD_LETTER_QUEUE =
            RabbitMqTopologyConfig.ADMIN_ACTION_REPLICATION_DEAD_LETTER_QUEUE;
    static final String FEEDBACK_QUEUE = RabbitMqTopologyConfig.RECOMMENDATION_FEEDBACK_QUEUE;
    static final String FEEDBACK_DEAD_LETTER_QUEUE =
            RabbitMqTopologyConfig.RECOMMENDATION_FEEDBACK_DEAD_LETTER_QUEUE;

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
        r.add("JWT_SECRET", () -> "analytics-outage-it-secret-32-characters!!");
        r.add("JWT_ISSUER", () -> "https://analytics-outage.it.local");
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
     * Completes a future per event id once the consumer's inbox call has returned successfully,
     * which is after the row reached ClickHouse and the transaction committed. A failed attempt
     * completes nothing, so a message requeued during the outage completes its future only when the
     * retry after recovery succeeds.
     */
    @TestConfiguration
    static class InboxProbeConfig {

        @Bean
        ConcurrentMap<UUID, CompletableFuture<Void>> processedEvents() {
            return new ConcurrentHashMap<>();
        }

        @Bean
        @Primary
        ProcessedMessageService probingProcessedMessageService(
                @Qualifier("processedMessageServiceImpl") ProcessedMessageService delegate,
                ConcurrentMap<UUID, CompletableFuture<Void>> processedEvents) {
            return (consumerName, eventId, eventType, handler) -> {
                var result = delegate.processOnce(consumerName, eventId, eventType, handler);
                // Only the two consumers under test complete a future: an event such as
                // post.liked.v1 is also routed to other queues, whose consumers must not release
                // a waiting test early.
                if (consumerName.equals("admin-action-replication-consumer")
                        || consumerName.equals("recommendation-feedback-consumer")) {
                    processedEvents
                            .computeIfAbsent(eventId, id -> new CompletableFuture<>())
                            .complete(null);
                }
                return result;
            };
        }
    }

    /** One audit row in PostgreSQL and the event that announces it. */
    private record Announced(UUID eventId, UUID actionId) {}

    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private AnalyticsIngestionController controller;
    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ConcurrentMap<UUID, CompletableFuture<Void>> processedEvents;

    @MockitoBean private GorseClient gorseClient;

    private Announced announce() {
        UUID actionId =
                jdbcClient
                        .sql(
                                "INSERT INTO admin_actions(action_type) VALUES ('ban_user')"
                                        + " RETURNING id")
                        .query(UUID.class)
                        .single();
        UUID eventId = UUID.randomUUID();
        processedEvents.put(eventId, new CompletableFuture<>());
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        AdminEventTypes.ACTION_RECORDED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        null,
                        "admin_action",
                        actionId,
                        Map.of("adminActionId", actionId.toString()));
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AdminEventTypes.ACTION_RECORDED_V1,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));
        return new Announced(eventId, actionId);
    }

    /** One engagement event by an existing account, and the post it names. */
    private record Engaged(UUID eventId, UUID postId) {}

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

    private Engaged engage(UUID userId) {
        UUID postId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        processedEvents.put(eventId, new CompletableFuture<>());
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        PostEventTypes.POST_LIKED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        userId,
                        "post",
                        postId,
                        Map.of("postId", postId.toString()));
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                PostEventTypes.POST_LIKED_V1,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));
        return new Engaged(eventId, postId);
    }

    private long countLikes(UUID userId) throws Exception {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count() FROM luvax_analytics.user_events FINAL WHERE"
                                        + " user_id = '"
                                        + userId
                                        + "' AND feedback_type = 'like'")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void awaitProcessed(Announced announced, long seconds) throws Exception {
        processedEvents.get(announced.eventId()).get(seconds, TimeUnit.SECONDS);
    }

    private com.rabbitmq.client.AMQP.Queue.DeclareOk queueState(String name) {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(name));
    }

    private long countRows(List<UUID> actionIds) throws Exception {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count() FROM luvax_analytics.admin_actions FINAL WHERE id"
                                        + " IN ("
                                        + String.join(
                                                ",",
                                                actionIds.stream()
                                                        .map(id -> "'" + id + "'")
                                                        .toList())
                                        + ")")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void outage_pausesIngestionKeepsMessagesReadyAndDrainsExactlyOnceAfterRecovery()
            throws Exception {
        assertThat(gate.attempt()).isTrue();
        controller.reconcile();
        assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
        CircuitBreaker breaker =
                breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME);
        breaker.reset();

        Announced healthy = announce();
        awaitProcessed(healthy, 30);

        CompletableFuture<Void> opened = new CompletableFuture<>();
        breaker.getEventPublisher()
                .onStateTransition(
                        event -> {
                            if (event.getStateTransition().getToState()
                                    == CircuitBreaker.State.OPEN) {
                                opened.complete(null);
                            }
                        });
        clickhouse.getDockerClient().pauseContainerCmd(clickhouse.getContainerId()).exec();
        List<Announced> sentDuringOutage = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                sentDuringOutage.add(announce());
            }

            opened.get(90, TimeUnit.SECONDS);
            controller.awaitIdle(30, TimeUnit.SECONDS);

            assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION))
                    .isFalse();
            assertThat(queueState(QUEUE).getConsumerCount()).isZero();
            assertThat(queueState(QUEUE).getMessageCount()).isEqualTo(sentDuringOutage.size());
            assertThat(queueState(DEAD_LETTER_QUEUE).getMessageCount()).isZero();
        } finally {
            clickhouse.getDockerClient().unpauseContainerCmd(clickhouse.getContainerId()).exec();
        }

        // The breaker half-opens by itself after its open wait, which starts the container again.
        Set<UUID> drained = new HashSet<>();
        for (Announced announced : sentDuringOutage) {
            awaitProcessed(announced, 120);
            drained.add(announced.eventId());
        }

        assertThat(drained)
                .containsExactlyInAnyOrderElementsOf(
                        sentDuringOutage.stream().map(Announced::eventId).toList());
        assertThat(queueState(QUEUE).getMessageCount()).isZero();
        assertThat(queueState(DEAD_LETTER_QUEUE).getMessageCount()).isZero();
        List<UUID> everything =
                new ArrayList<>(sentDuringOutage.stream().map(Announced::actionId).toList());
        everything.add(healthy.actionId());
        assertThat(countRows(everything)).isEqualTo(everything.size());
        assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
    }

    @Test
    void outage_engagementEvents_neverReachGorseUntilClickHouseHasThemAndDrainExactlyOnce()
            throws Exception {
        assertThat(gate.attempt()).isTrue();
        controller.reconcile();
        assertThat(controller.isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isTrue();
        CircuitBreaker breaker =
                breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME);
        breaker.reset();
        UUID user = insertUser("outage_engagement_user");

        Engaged healthy = engage(user);
        processedEvents.get(healthy.eventId()).get(30, TimeUnit.SECONDS);
        verify(gorseClient, times(1)).insertFeedback(anyList());

        CompletableFuture<Void> opened = new CompletableFuture<>();
        breaker.getEventPublisher()
                .onStateTransition(
                        event -> {
                            if (event.getStateTransition().getToState()
                                    == CircuitBreaker.State.OPEN) {
                                opened.complete(null);
                            }
                        });
        clickhouse.getDockerClient().pauseContainerCmd(clickhouse.getContainerId()).exec();
        List<Engaged> sentDuringOutage = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                sentDuringOutage.add(engage(user));
            }

            opened.get(90, TimeUnit.SECONDS);
            controller.awaitIdle(30, TimeUnit.SECONDS);

            assertThat(controller.isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK))
                    .isFalse();
            assertThat(queueState(FEEDBACK_QUEUE).getConsumerCount()).isZero();
            assertThat(queueState(FEEDBACK_QUEUE).getMessageCount())
                    .isEqualTo(sentDuringOutage.size());
            assertThat(queueState(FEEDBACK_DEAD_LETTER_QUEUE).getMessageCount()).isZero();
            // ClickHouse is written first, so none of the stranded events reached the recommender.
            verify(gorseClient, times(1)).insertFeedback(anyList());
        } finally {
            clickhouse.getDockerClient().unpauseContainerCmd(clickhouse.getContainerId()).exec();
        }

        for (Engaged engaged : sentDuringOutage) {
            processedEvents.get(engaged.eventId()).get(120, TimeUnit.SECONDS);
        }

        assertThat(queueState(FEEDBACK_QUEUE).getMessageCount()).isZero();
        assertThat(queueState(FEEDBACK_DEAD_LETTER_QUEUE).getMessageCount()).isZero();
        assertThat(countLikes(user)).isEqualTo(1 + sentDuringOutage.size());
        verify(gorseClient, times(1 + sentDuringOutage.size())).insertFeedback(anyList());
        assertThat(controller.isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isTrue();
    }
}
