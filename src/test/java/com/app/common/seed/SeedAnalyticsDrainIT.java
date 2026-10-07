package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.ClickHouseOperations;
import com.app.common.analytics.ingest.AnalyticsIngestionController;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.inbox.enums.ProcessedMessageResult;
import com.app.common.inbox.service.ProcessedMessageService;
import com.app.common.outbox.service.OutboxPublisherService;
import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;
import com.app.common.seed.outbox.SeedOutboxEmitter;
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.AnalyticsSeedWriter;
import com.app.common.seed.writer.CommentSeedWriter;
import com.app.common.seed.writer.EngagementSeedWriter;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.MessageSeedWriter;
import com.app.common.seed.writer.ModerationSeedWriter;
import com.app.common.seed.writer.NotificationSeedWriter;
import com.app.common.seed.writer.PostSeedWriter;
import com.app.common.seed.writer.SocialGraphSeedWriter;
import com.app.common.seed.writer.StorySeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the seeded analytics reach ClickHouse through the real outbox publisher, RabbitMQ and the
 * three analytics consumers, and agree with PostgreSQL once drained.
 *
 * <p>The full seed enqueues about 59,000 analytics events, which the publisher's default rate needs
 * ten minutes to drain, far too long for a test. So the events the seed really produced are kept in
 * full for the audit log and cut down to the newest 48 statistics buckets and the first 500
 * behavioural events, and the assertions compare ClickHouse with what was kept. The full volume is
 * drained by the local rehearsal instead.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.initial-delay=PT1H",
            "app.outbox.publisher.fixed-delay=PT1H",
            "app.outbox.publisher.batch-size=500",
            "app.outbox.publisher.confirm-timeout=PT10S",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true",
            "app.mail.consumer.enabled=false",
            "app.notification.consumer.enabled=false",
            "app.comment.consumer.enabled=false",
            "app.story.consumer.enabled=false",
            "app.admin.consumer.enabled=false",
            "app.hashtag.consumer.enabled=false",
            "app.post.consumer.enabled=false",
            "app.recommendation.consumer.enabled=false",
            // The job would add an audit row mid-test and skew the Postgres/ClickHouse counts
            "app.admin.suspension-expiry.enabled=false"
        })
@Testcontainers
@Import(SeedAnalyticsDrainIT.InboxProbeConfig.class)
class SeedAnalyticsDrainIT {

    private static final String ADMIN_ACTION_EVENT = "admin.action.recorded.v1";
    private static final String STATS_EVENT = "admin.platform-stats.collected.v1";
    private static final String IMPORT_EVENT = "recommendation.user-event.imported.v1";
    private static final int KEPT_BUCKETS = 48;
    private static final int KEPT_IMPORTS = 500;

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
    static void register(DynamicPropertyRegistry registry) {
        ClickHouseTestSupport.register(registry, clickhouse);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("JWT_SECRET", () -> "seed-analytics-drain-it-secret-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://seedanalyticsdrain.it.local");
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
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
        registry.add("app.stats.enabled", () -> false);
    }

    /**
     * Completes a future per event id once the consumer's inbox call has returned, which is after
     * the handler wrote to ClickHouse, so a test can wait for a known set of events without polling
     * either store.
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

    @MockitoBean private MailService mailService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private ClickHouseOperations clickHouse;
    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private AnalyticsIngestionController controller;
    @Autowired private OutboxPublisherService publisher;
    @Autowired private SeedResetService seedResetService;
    @Autowired private SeedOutboxEmitter seedOutboxEmitter;
    @Autowired private UserSeedWriter userSeedWriter;
    @Autowired private MediaSeedWriter mediaSeedWriter;
    @Autowired private PostSeedWriter postSeedWriter;
    @Autowired private CommentSeedWriter commentSeedWriter;
    @Autowired private EngagementSeedWriter engagementSeedWriter;
    @Autowired private SocialGraphSeedWriter socialGraphSeedWriter;
    @Autowired private StorySeedWriter storySeedWriter;
    @Autowired private MessageSeedWriter messageSeedWriter;
    @Autowired private NotificationSeedWriter notificationSeedWriter;
    @Autowired private ModerationSeedWriter moderationSeedWriter;
    @Autowired private AnalyticsSeedWriter analyticsSeedWriter;

    @Autowired
    private ConcurrentMap<UUID, CompletableFuture<ProcessedMessageResult>> processedEvents;

    @Test
    void seededAnalytics_drainThroughTheConsumersAndAgreeWithPostgres() throws Exception {
        assertThat(gate.attempt()).isTrue();
        seedEverything();
        keepARepresentativeSlice();

        List<UUID> expected =
                jdbcTemplate.queryForList(
                        "SELECT event_id FROM outbox_events WHERE event_type IN (?, ?, ?)",
                        UUID.class,
                        ADMIN_ACTION_EVENT,
                        STATS_EVENT,
                        IMPORT_EVENT);
        List<CompletableFuture<ProcessedMessageResult>> done =
                expected.stream()
                        .map(
                                id ->
                                        processedEvents.computeIfAbsent(
                                                id, k -> new CompletableFuture<>()))
                        .toList();

        // Releasing a suspension nobody holds reconciles the listener containers synchronously.
        controller.resume("test");
        int attempted;
        do {
            attempted = publisher.publishDueEvents();
        } while (attempted > 0);
        CompletableFuture.allOf(done.toArray(new CompletableFuture[0])).get(3, TimeUnit.MINUTES);

        int auditRows = count("SELECT COUNT(*) FROM admin_actions");
        assertThat(auditRows).isPositive();
        assertThat(clickHouseCount("SELECT count() FROM admin_actions FINAL")).isEqualTo(auditRows);
        assertThat(clickHouseCount("SELECT uniqExact(bucket_start) FROM platform_stats"))
                .isEqualTo(KEPT_BUCKETS);
        assertThat(clickHouseCount("SELECT count() FROM user_events FINAL"))
                .isEqualTo(KEPT_IMPORTS);
        // Imported events must never be replayed into the recommender.
        assertThat(
                        clickHouseCount(
                                "SELECT count() FROM user_events FINAL WHERE feedback_type IS NOT"
                                        + " NULL"))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED'"))
                .isZero();
        assertThat(
                        rabbitTemplate.receive(
                                RabbitMqTopologyConfig.ADMIN_ACTION_REPLICATION_DEAD_LETTER_QUEUE,
                                200))
                .isNull();
        assertThat(
                        rabbitTemplate.receive(
                                RabbitMqTopologyConfig.PLATFORM_STATS_DEAD_LETTER_QUEUE, 200))
                .isNull();
    }

    // The same chain SeedRunner runs, up to and including the outbox emission.
    private void seedEverything() {
        seedResetService.reset();
        SeedContent content = new SeedDataLoader().load();
        SeedTimeline timeline = new SeedTimeline(20260825L, Instant.parse("2026-08-25T00:00:00Z"));

        Map<String, UUID> usersByUsername = userSeedWriter.write(content, timeline);
        Map<String, UUID> mediaByCompositeKey = mediaSeedWriter.write(content, usersByUsername);
        Map<String, UUID> postIdBySeedId =
                postSeedWriter.write(content, usersByUsername, mediaByCompositeKey, timeline);
        List<UUID> commentIds =
                commentSeedWriter.write(content, usersByUsername, postIdBySeedId, timeline);
        engagementSeedWriter.write(content, usersByUsername, postIdBySeedId, commentIds, timeline);
        socialGraphSeedWriter.write(content, usersByUsername, timeline);
        storySeedWriter.write(content, usersByUsername, timeline);
        messageSeedWriter.write(
                content, usersByUsername, mediaByCompositeKey, postIdBySeedId, timeline);
        moderationSeedWriter.write(content, usersByUsername, postIdBySeedId, timeline);
        notificationSeedWriter.write(timeline);
        analyticsSeedWriter.write(timeline);
        seedOutboxEmitter.emitFullVolume(content, usersByUsername, postIdBySeedId);
    }

    // Everything but the analytics events goes, because their consumers are not part of this test,
    // and the statistics buckets and behavioural events are cut down as the class comment says.
    private void keepARepresentativeSlice() {
        jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE event_type NOT IN (?, ?, ?)",
                ADMIN_ACTION_EVENT,
                STATS_EVENT,
                IMPORT_EVENT);
        jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE event_type = ? AND event_id NOT IN"
                        + " (SELECT event_id FROM outbox_events WHERE event_type = ?"
                        + " ORDER BY payload->'data'->>'bucketStart' DESC LIMIT ?)",
                STATS_EVENT,
                STATS_EVENT,
                KEPT_BUCKETS);
        jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE event_type = ? AND event_id NOT IN"
                        + " (SELECT event_id FROM outbox_events WHERE event_type = ?"
                        + " ORDER BY payload->'data'->>'createdAt', event_id LIMIT ?)",
                IMPORT_EVENT,
                IMPORT_EVENT,
                KEPT_IMPORTS);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM outbox_events WHERE event_type = '"
                                        + STATS_EVENT
                                        + "'"))
                .isEqualTo(KEPT_BUCKETS);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM outbox_events WHERE event_type = '"
                                        + IMPORT_EVENT
                                        + "'"))
                .isEqualTo(KEPT_IMPORTS);
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private long clickHouseCount(String sql) {
        return clickHouse.read(
                "seed-analytics-drain-it", client -> client.sql(sql).query(Long.class).single());
    }
}
