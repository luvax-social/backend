package com.app.modules.recommendation.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
import org.springframework.transaction.support.TransactionTemplate;
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
import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.common.outbox.service.OutboxPublisherService;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * The interaction control signals end to end: each event is enqueued through the transactional
 * outbox, published to the broker by the real publisher, routed by the real bindings and written by
 * the import consumer, and lands as exactly one row in ClickHouse with the mapped type, entity,
 * metadata, actor and the event's own time.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.initial-delay=PT1H",
            "app.outbox.publisher.fixed-delay=PT1H",
            "app.outbox.publisher.confirm-timeout=PT5S"
        })
@Testcontainers
@Import(InteractionSignalAnalyticsIT.InboxProbeConfig.class)
class InteractionSignalAnalyticsIT {

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
        r.add("JWT_SECRET", () -> "interaction-signal-it-secret-32-characters!!");
        r.add("JWT_ISSUER", () -> "https://interaction-signal.it.local");
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
     * the handler wrote to ClickHouse and its transaction committed, so a test waits for the
     * consumer without polling either store.
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
    @Autowired private OutboxService outboxService;
    @Autowired private OutboxPublisherService outboxPublisherService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private RabbitTemplate rabbitTemplate;

    @Autowired
    private ConcurrentMap<UUID, CompletableFuture<ProcessedMessageResult>> processedEvents;

    private final JdbcClient clickHouse =
            ClickHouseTestSupport.clientAs(
                    clickhouse, clickhouse.getUsername(), clickhouse.getPassword());

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

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
    void showFewer_landsAsAnAccountShowFewerRowAboutTheAuthor() throws Exception {
        OutboxEvent event =
                publish(
                        SocialEventTypes.USER_SHOW_FEWER_V1,
                        actor,
                        Map.of("authorId", target.toString()));

        Stored row = stored(event);

        assertThat(row.userId()).isEqualTo(actor);
        assertThat(row.eventType()).isEqualTo("account_show_fewer");
        assertThat(row.entityType()).isEqualTo("user");
        assertThat(row.entityId()).isEqualTo(target);
        assertThat(row.metadata()).isEmpty();
        assertThat(row.createdAt()).isEqualTo(occurredAt(event));
        assertThat(row.feedbackType()).isNull();
    }

    @Test
    void showFewerUndone_landsAsAnAccountShowFewerUndoRow() throws Exception {
        OutboxEvent event =
                publish(
                        SocialEventTypes.USER_SHOW_FEWER_UNDONE_V1,
                        actor,
                        Map.of("authorId", target.toString()));

        Stored row = stored(event);

        assertThat(row.userId()).isEqualTo(actor);
        assertThat(row.eventType()).isEqualTo("account_show_fewer_undo");
        assertThat(row.entityType()).isEqualTo("user");
        assertThat(row.entityId()).isEqualTo(target);
        assertThat(row.createdAt()).isEqualTo(occurredAt(event));
    }

    @Test
    void muted_landsAsAnAccountMuteRowCarryingTheScopes() throws Exception {
        OutboxEvent event =
                publish(
                        SocialEventTypes.USER_MUTED_V1,
                        actor,
                        Map.of(
                                "targetUserId",
                                target.toString(),
                                "mutePosts",
                                true,
                                "muteStories",
                                false));

        Stored row = stored(event);

        assertThat(row.userId()).isEqualTo(actor);
        assertThat(row.eventType()).isEqualTo("account_mute");
        assertThat(row.entityType()).isEqualTo("user");
        assertThat(row.entityId()).isEqualTo(target);
        assertThat(row.metadata()).isEqualTo("{\"posts\":true,\"stories\":false}");
        assertThat(row.createdAt()).isEqualTo(occurredAt(event));
    }

    @Test
    void unmuted_landsAsAnAccountUnmuteRow() throws Exception {
        OutboxEvent event =
                publish(
                        SocialEventTypes.USER_UNMUTED_V1,
                        actor,
                        Map.of("targetUserId", target.toString()));

        Stored row = stored(event);

        assertThat(row.userId()).isEqualTo(actor);
        assertThat(row.eventType()).isEqualTo("account_unmute");
        assertThat(row.entityType()).isEqualTo("user");
        assertThat(row.entityId()).isEqualTo(target);
        assertThat(row.createdAt()).isEqualTo(occurredAt(event));
    }

    @Test
    void interactionLimitChanged_landsAsARowAboutTheAccountWithoutTheEndTime() throws Exception {
        OutboxEvent event =
                publish(
                        SocialEventTypes.USER_INTERACTION_LIMIT_CHANGED_V1,
                        actor,
                        Map.of(
                                "limitNonFollowers",
                                true,
                                "limitRecentFollowers",
                                true,
                                "expiresAt",
                                "2026-08-16T09:30:00Z"));

        Stored row = stored(event);

        assertThat(row.userId()).isEqualTo(actor);
        assertThat(row.eventType()).isEqualTo("interaction_limit_change");
        assertThat(row.entityType()).isEqualTo("user");
        assertThat(row.entityId()).isEqualTo(actor);
        assertThat(row.metadata())
                .isEqualTo("{\"nonFollowers\":true,\"recentFollowers\":true,\"active\":true}");
        assertThat(row.createdAt()).isEqualTo(occurredAt(event));
    }

    @Test
    void commentUnhidden_landsAsACommentUnhideRowForEachReleasedComment() throws Exception {
        UUID postId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        OutboxEvent firstEvent =
                publishComment(CommentEventTypes.COMMENT_UNHIDDEN_V1, first, actor, postId, null);
        OutboxEvent secondEvent =
                publishComment(CommentEventTypes.COMMENT_UNHIDDEN_V1, second, actor, postId, null);

        Stored firstRow = stored(firstEvent);
        Stored secondRow = stored(secondEvent);

        assertThat(firstRow.userId()).isEqualTo(actor);
        assertThat(firstRow.eventType()).isEqualTo("comment_unhide");
        assertThat(firstRow.entityType()).isEqualTo("comment");
        assertThat(firstRow.entityId()).isEqualTo(first);
        assertThat(firstRow.metadata()).isEmpty();
        assertThat(firstRow.createdAt()).isEqualTo(occurredAt(firstEvent));
        assertThat(secondRow.entityId()).isEqualTo(second);
    }

    @Test
    void commentHidden_landsAsACommentHiddenRowCarryingTheReasonOnly() throws Exception {
        UUID commentId = UUID.randomUUID();

        OutboxEvent event =
                publishComment(
                        CommentEventTypes.COMMENT_HIDDEN_V1,
                        commentId,
                        actor,
                        UUID.randomUUID(),
                        "limited_interaction");

        Stored row = stored(event);

        assertThat(row.userId()).isEqualTo(actor);
        assertThat(row.eventType()).isEqualTo("comment_hidden");
        assertThat(row.entityType()).isEqualTo("comment");
        assertThat(row.entityId()).isEqualTo(commentId);
        assertThat(row.metadata()).isEqualTo("{\"reason\":\"limited_interaction\"}");
        assertThat(row.createdAt()).isEqualTo(occurredAt(event));
    }

    @Test
    void redelivery_foldsIntoTheSingleRow() throws Exception {
        OutboxEvent event =
                publish(
                        SocialEventTypes.USER_MUTED_V1,
                        actor,
                        Map.of(
                                "targetUserId",
                                target.toString(),
                                "mutePosts",
                                true,
                                "muteStories",
                                true));
        stored(event);

        // The broker delivers the very same event again: the inbox skips the handler, and the
        // single row already there is all the table holds for that id.
        processedEvents.put(event.getEventId(), new CompletableFuture<>());
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                SocialEventTypes.USER_MUTED_V1,
                new Message(
                        DomainEventEnvelopeJson.write(event.getPayload())
                                .getBytes(StandardCharsets.UTF_8)));
        processedEvents.get(event.getEventId()).get(60, TimeUnit.SECONDS);

        assertThat(rowsFor(event)).isEqualTo(1L);
    }

    private OutboxEvent publish(String eventType, UUID actorId, Map<String, Object> data)
            throws Exception {
        OutboxEvent event =
                transactionTemplate.execute(
                        status ->
                                outboxService.enqueue(
                                        eventType, eventType, "user", actorId, actorId, data));
        return deliver(event);
    }

    private OutboxEvent publishComment(
            String eventType, UUID commentId, UUID actorId, UUID postId, String reason)
            throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId.toString());
        data.put("commentId", commentId.toString());
        if (reason != null) {
            data.put("reason", reason);
        }
        OutboxEvent event =
                transactionTemplate.execute(
                        status ->
                                outboxService.enqueue(
                                        eventType, eventType, "comment", commentId, actorId, data));
        return deliver(event);
    }

    // Registers the wait before the publisher runs, so the consumer cannot finish unobserved.
    private OutboxEvent deliver(OutboxEvent event) {
        processedEvents.computeIfAbsent(event.getEventId(), id -> new CompletableFuture<>());
        outboxPublisherService.publishDueEvents();
        return event;
    }

    private Stored stored(OutboxEvent event) throws Exception {
        processedEvents
                .computeIfAbsent(event.getEventId(), id -> new CompletableFuture<>())
                .get(60, TimeUnit.SECONDS);
        assertThat(rowsFor(event)).isEqualTo(1L);
        return clickHouse
                .sql(
                        "SELECT user_id, toString(event_type) AS event_type, entity_type,"
                                + " entity_id, metadata, created_at, feedback_type FROM"
                                + " user_events FINAL WHERE id = ?")
                .param(event.getEventId())
                .query(
                        (rows, rowNumber) ->
                                new Stored(
                                        rows.getObject("user_id", UUID.class),
                                        rows.getString("event_type"),
                                        rows.getString("entity_type"),
                                        rows.getObject("entity_id", UUID.class),
                                        rows.getString("metadata"),
                                        rows.getObject("created_at", OffsetDateTime.class)
                                                .toInstant()
                                                .atOffset(java.time.ZoneOffset.UTC),
                                        rows.getString("feedback_type")))
                .single();
    }

    private long rowsFor(OutboxEvent event) {
        Long rows =
                clickHouse
                        .sql("SELECT count() FROM user_events FINAL WHERE id = ?")
                        .param(event.getEventId())
                        .query(Long.class)
                        .single();
        return rows;
    }

    // ClickHouse keeps microseconds, so the expected time is the event's own, cut to that.
    private static OffsetDateTime occurredAt(OutboxEvent event) {
        DomainEventEnvelope envelope = event.getPayload();
        return envelope.occurredAt()
                .toInstant()
                .truncatedTo(ChronoUnit.MICROS)
                .atOffset(java.time.ZoneOffset.UTC);
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
