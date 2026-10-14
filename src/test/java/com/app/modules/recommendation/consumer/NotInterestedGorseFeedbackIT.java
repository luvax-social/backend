package com.app.modules.recommendation.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.ingest.AnalyticsIngestionController;
import com.app.common.analytics.ingest.AnalyticsListenerIds;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.recommendation.client.GorseClient;
import com.app.modules.recommendation.client.dto.GorseFeedback;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * A viewer's "not interested" preference through the real consumer, RabbitMQ, ClickHouse and a
 * Gorse 0.5.11 configured like production. It proves the observable end state of each case: the
 * ClickHouse row, and whether Gorse holds the {@code not_interested} tuple, including when the
 * apply and undo events arrive out of order and when an undo finds nothing to delete.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
@Import(NotInterestedGorseFeedbackIT.InboxProbeConfig.class)
class NotInterestedGorseFeedbackIT {

    private static final String GORSE_API_KEY = "not-interested-it-key";
    private static final String NOT_INTERESTED = GorseFeedback.NOT_INTERESTED_TYPE;

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();
    static final Network network = Network.newNetwork();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES)
                    .withNetwork(network)
                    .withNetworkAliases("gorse-postgres");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    static GenericContainer<?> gorse;

    @BeforeAll
    static void startGorse() throws Exception {
        postgres.execInContainer(
                "psql",
                "-U",
                postgres.getUsername(),
                "-d",
                "postgres",
                "-c",
                "CREATE DATABASE gorse");
        String store =
                "postgres://"
                        + postgres.getUsername()
                        + ":"
                        + postgres.getPassword()
                        + "@gorse-postgres:5432/gorse?sslmode=disable";
        gorse =
                new GenericContainer<>(DockerImageName.parse("zhenghaoz/gorse-in-one:0.5.11"))
                        .withNetwork(network)
                        .withExposedPorts(8088)
                        // The repository's own configuration, so a regression in the
                        // negative_feedback_types line fails this class at startup.
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(
                                        Path.of("gorse", "config", "config.toml").toAbsolutePath()),
                                "/etc/gorse/config.toml")
                        .withEnv("GORSE_DATA_STORE", store)
                        .withEnv("GORSE_CACHE_STORE", store)
                        .withEnv("GORSE_SERVER_API_KEY", GORSE_API_KEY)
                        .withEnv("GORSE_DASHBOARD_USER_NAME", "dashboard")
                        .withEnv("GORSE_DASHBOARD_PASSWORD", "dashboard")
                        .withCommand(
                                "-c",
                                "/etc/gorse/config.toml",
                                "--log-path",
                                "/var/lib/gorse/gorse.log",
                                "--cache-path",
                                "/var/lib/gorse/master")
                        .waitingFor(Wait.forLogMessage(".*start http server.*", 1));
        gorse.start();
    }

    @AfterAll
    static void stopGorse() {
        if (gorse != null) {
            gorse.stop();
        }
    }

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        ClickHouseTestSupport.register(r, clickhouse);
        r.add(
                "app.gorse.base-url",
                () -> "http://" + gorse.getHost() + ":" + gorse.getMappedPort(8088));
        r.add("app.gorse.api-key", () -> GORSE_API_KEY);
        r.add("app.gorse.read-timeout", () -> "PT20S");
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> "not-interested-it-secret-that-is-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://not-interested.it.local");
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
     * the handler wrote to ClickHouse and called Gorse. It lets a test wait for the consumer to
     * finish an event without polling either store, and completes exceptionally when the handler
     * failed.
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
    @Autowired private JdbcTemplate jdbc;

    @Autowired
    private ConcurrentMap<UUID, CompletableFuture<ProcessedMessageResult>> processedEvents;

    @MockitoSpyBean private GorseClient gorseClient;

    private final JdbcClient clickHouse =
            ClickHouseTestSupport.clientAs(
                    clickhouse, clickhouse.getUsername(), clickhouse.getPassword());

    private UUID viewer;
    private UUID author;
    private UUID post;

    @BeforeEach
    void startIngestionAndSeedPostgres() {
        assertThat(gate.attempt()).isTrue();
        // Releasing a suspension nobody holds reconciles the listener containers synchronously, so
        // the consumer is running before the test publishes.
        controller.resume("test");
        assertThat(controller.isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isTrue();
        breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME).reset();
        clickHouse.sql("TRUNCATE TABLE user_events").update();
        reset(gorseClient);
        viewer = insertUser("ni_viewer");
        author = insertUser("ni_author");
        post = insertPost(author);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM post_not_interested");
        jdbc.update("DELETE FROM posts");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void markedPost_reachesClickHouseAndGorseAsNotInterested() throws Exception {
        mark();

        UUID eventId = publishApply();

        Stored stored = stored(eventId);
        assertThat(stored.userId()).isEqualTo(viewer);
        assertThat(stored.eventType()).isEqualTo("post_not_interested");
        assertThat(stored.entityType()).isEqualTo("post");
        assertThat(stored.entityId()).isEqualTo(post);
        assertThat(stored.feedbackType()).isEqualTo(NOT_INTERESTED);
        assertThat(stored.feedbackValue()).isEqualTo(1.0);
        assertThat(heldInGorse()).get().extracting(GorseFeedback::value).isEqualTo(1.0);
        verify(gorseClient, never()).hideItem(any());
    }

    @Test
    void undonePreference_isRecordedWithoutFeedbackColumnsAndRemovedFromGorse() throws Exception {
        mark();
        publishApply();
        assertThat(heldInGorse()).isPresent();
        unmark();

        UUID undoId = publishUndo();

        Stored stored = stored(undoId);
        assertThat(stored.eventType()).isEqualTo("post_not_interested_undo");
        assertThat(stored.entityId()).isEqualTo(post);
        assertThat(stored.feedbackType()).isNull();
        assertThat(stored.feedbackValue()).isNull();
        assertThat(heldInGorse()).isEmpty();
        verify(gorseClient, never()).hideItem(any());
    }

    @Test
    void applyDeliveredAfterTheUndo_keepsTheRowButGorseHoldsNothing() throws Exception {
        // The viewer marked and then undid the post; the undo event overtook the mark event, so
        // the mark is handled with no row left in PostgreSQL.
        UUID eventId = publishApply();

        assertThat(stored(eventId).feedbackType()).isEqualTo(NOT_INTERESTED);
        assertThat(heldInGorse()).isEmpty();
    }

    @Test
    void undoDeliveredAfterALaterReapply_leavesTheNewerPreferenceInGorse() throws Exception {
        mark();
        publishApply();
        assertThat(heldInGorse()).isPresent();

        // The row exists again by the time the older undo is handled.
        UUID undoId = publishUndo();

        assertThat(stored(undoId).eventType()).isEqualTo("post_not_interested_undo");
        assertThat(heldInGorse()).isPresent();
    }

    @Test
    void undoOfATupleGorseDoesNotHold_isStillAcknowledged() throws Exception {
        // Deleting a tuple that was never there is what a replayed undo does against the real
        // Gorse; the event must complete instead of failing and retrying.
        UUID undoId = publishUndo();

        assertThat(stored(undoId).eventType()).isEqualTo("post_not_interested_undo");
        assertThat(heldInGorse()).isEmpty();
    }

    private UUID publishApply() throws Exception {
        return publish(
                PostEventTypes.POST_NOT_INTERESTED_V1,
                Map.of("postId", post.toString(), "authorId", author.toString()));
    }

    private UUID publishUndo() throws Exception {
        return publish(
                PostEventTypes.POST_NOT_INTERESTED_UNDONE_V1, Map.of("postId", post.toString()));
    }

    private UUID publish(String eventType, Map<String, Object> data) throws Exception {
        UUID eventId = UUID.randomUUID();
        processedEvents.put(eventId, new CompletableFuture<>());
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        eventType,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        viewer,
                        "post",
                        post,
                        data);
        rabbitTemplate.send(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                eventType,
                new Message(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8)));
        processedEvents.get(eventId).get(60, TimeUnit.SECONDS);
        return eventId;
    }

    private Optional<GorseFeedback> heldInGorse() {
        return gorseClient.getFeedback(NOT_INTERESTED, viewer.toString(), post.toString());
    }

    private void mark() {
        jdbc.update(
                "INSERT INTO post_not_interested (user_id, post_id) VALUES (?, ?)", viewer, post);
    }

    private void unmark() {
        jdbc.update(
                "DELETE FROM post_not_interested WHERE user_id = ? AND post_id = ?", viewer, post);
    }

    private UUID insertUser(String prefix) {
        UUID id = UUID.randomUUID();
        String username = prefix + "_" + id.toString().substring(0, 4);
        jdbc.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified)"
                        + " VALUES (?, ?, ?, 'user', 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local");
        return id;
    }

    private UUID insertPost(UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status)"
                        + " VALUES (?, ?, 'caption', 'text', CAST('published' AS post_status))",
                id,
                authorId);
        return id;
    }

    private Stored stored(UUID eventId) {
        return clickHouse
                .sql(
                        "SELECT user_id, toString(event_type) AS event_type, entity_type,"
                                + " entity_id, feedback_type, feedback_value FROM user_events"
                                + " FINAL WHERE id = ?")
                .param(eventId)
                .query(
                        (rows, rowNumber) ->
                                new Stored(
                                        rows.getObject("user_id", UUID.class),
                                        rows.getString("event_type"),
                                        rows.getString("entity_type"),
                                        rows.getObject("entity_id", UUID.class),
                                        rows.getString("feedback_type"),
                                        rows.getObject("feedback_value", Double.class)))
                .single();
    }

    private record Stored(
            UUID userId,
            String eventType,
            String entityType,
            UUID entityId,
            String feedbackType,
            Double feedbackValue) {}
}
