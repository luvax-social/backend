package com.app.modules.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.admin.messaging.AdminEventTypes;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;
import com.app.modules.admin.service.PlatformStatsCollectionService;
import com.app.modules.admin.service.StatsBuckets;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Exercises collection against real SQL and the outbox.
 *
 * <p>The aggregation rules live in statements rather than in Java, so a unit test with a mocked
 * repository would prove nothing about them. Collection no longer writes a table: it computes every
 * metric in PostgreSQL and enqueues one event carrying the bucket, so what is asserted here is the
 * content of that event.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class PlatformStatsIT {

    private static final Duration INTERVAL = Duration.ofMinutes(30);

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "platform-stats-it-secret-32-chars-min!!!!");
        registry.add("JWT_ISSUER", () -> "https://platformstats.it.local");
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
        // The scheduled pass must not race the explicit calls this class makes.
        registry.add("app.stats.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformStatsCollectionService collectionService;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE event_type = ?",
                AdminEventTypes.PLATFORM_STATS_COLLECTED_V1);
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void collectBucket_enqueuesOneEventCarryingGaugesAndFlowsForTheBucket() {
        OffsetDateTime bucket = pastBucket(4);
        createUser("stats_inside", "user", bucket.plusMinutes(5));
        createUser("stats_before", "user", bucket.minusHours(2));

        collectionService.collectBucket(bucket);

        assertThat(eventsFor(bucket)).hasSize(1);
        Map<String, Long> rows = rowsOf(latestEvent(bucket));
        // The gauge counts everything that existed by the end of the bucket.
        assertThat(rows.get("users_total|")).isEqualTo(2L);
        assertThat(rows.get("users_by_role|user")).isEqualTo(2L);
        assertThat(rows.get("users_by_status|active")).isEqualTo(2L);
        // The flow counts only what happened inside it.
        assertThat(rows.get("registrations|")).isEqualTo(1L);
    }

    @Test
    void collectBucket_carriesTheBucketIdentityAndWidth() {
        OffsetDateTime bucket = pastBucket(4);

        collectionService.collectBucket(bucket);

        DomainEventEnvelope event = latestEvent(bucket);
        assertThat(event.eventType()).isEqualTo(AdminEventTypes.PLATFORM_STATS_COLLECTED_V1);
        assertThat(event.aggregateType()).isEqualTo(PlatformStatsCollectedEvent.AGGREGATE_TYPE);
        assertThat(event.aggregateId())
                .isEqualTo(PlatformStatsCollectedEvent.aggregateId(bucket.toInstant()));
        assertThat(((Number) event.data().get("bucketSeconds")).longValue())
                .isEqualTo(INTERVAL.toSeconds());
        assertThat(PlatformStatsCollectedEvent.parse(event.data()).bucketStart())
                .isEqualTo(bucket.toInstant());
    }

    @Test
    void collectBucket_gaugeIgnoresRowsCreatedAfterTheBucketEnded() {
        OffsetDateTime bucket = pastBucket(4);
        createUser("stats_later", "user", bucket.plusHours(1));

        collectionService.collectBucket(bucket);

        // Bounding the gauge by the bucket end rather than by "now" is what lets the job be re-run
        // for a past bucket without overwriting that bucket's history with the present.
        assertThat(rowsOf(latestEvent(bucket)).getOrDefault("users_total|", 0L)).isZero();
    }

    @Test
    void collectBucket_rerun_restatesTheSameBucketWithALaterComputationTime() {
        OffsetDateTime bucket = pastBucket(4);
        createUser("stats_rerun", "user", bucket.plusMinutes(1));

        collectionService.collectBucket(bucket);
        collectionService.collectBucket(bucket);

        List<DomainEventEnvelope> events = eventsFor(bucket);
        assertThat(events).hasSize(2);
        // Both events name the same bucket and carry the same numbers, and the store keeps the one
        // computed last, so a double run restates rather than doubles.
        assertThat(events)
                .extracting(DomainEventEnvelope::aggregateId)
                .containsOnly(PlatformStatsCollectedEvent.aggregateId(bucket.toInstant()));
        assertThat(rowsOf(events.get(1))).isEqualTo(rowsOf(events.get(0)));
        Instant first = PlatformStatsCollectedEvent.parse(events.get(0).data()).computedAt();
        Instant second = PlatformStatsCollectedEvent.parse(events.get(1).data()).computedAt();
        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    void collectBucket_flowIsUnchangedByADeletionInsideTheWindow() {
        OffsetDateTime bucket = pastBucket(4);
        UUID author = createUser("stats_author", "user", bucket.minusDays(1));
        UUID post = createPost(author, bucket.plusMinutes(2));
        collectionService.collectBucket(bucket);
        long before = rowsOf(eventsFor(bucket).get(0)).get("posts_created|");

        // A moderation sweep after the fact. A flow derived by subtracting gauge snapshots would
        // now read as a negative number; a direct range count over the window does not move.
        jdbcTemplate.update(
                "UPDATE posts SET deleted_at = NOW(), status = 'removed' WHERE id = ?", post);
        collectionService.collectBucket(bucket);

        Map<String, Long> after = rowsOf(latestEvent(bucket));
        assertThat(before).isEqualTo(1L);
        assertThat(after.get("posts_created|")).isEqualTo(1L);
        // The gauge does move, which is the point of the distinction.
        assertThat(after.getOrDefault("posts_total|", 0L)).isZero();
    }

    @Test
    void collectBucket_writesNothingToPostgresBesidesTheOutboxRow() {
        OffsetDateTime bucket = pastBucket(4);

        collectionService.collectBucket(bucket);

        Integer statsTables =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables"
                                + " WHERE table_name = 'platform_stats'",
                        Integer.class);
        assertThat(statsTables).isZero();
    }

    private static OffsetDateTime pastBucket(int bucketsBack) {
        return StatsBuckets.floor(OffsetDateTime.now(ZoneOffset.UTC).toInstant(), INTERVAL)
                .minus(INTERVAL.multipliedBy(bucketsBack));
    }

    private UUID createUser(String username, String role, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified,"
                        + " created_at) VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE,"
                        + " TRUE, ?)",
                id,
                username,
                username + "@test.local",
                role,
                createdAt);
        return id;
    }

    private UUID createPost(UUID author, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts (user_id, caption, post_type, status, created_at)"
                        + " VALUES (?, 'caption', 'text', 'published', ?) RETURNING id",
                UUID.class,
                author,
                createdAt);
    }

    private List<DomainEventEnvelope> eventsFor(OffsetDateTime bucket) {
        return jdbcTemplate
                .queryForList(
                        "SELECT payload::text FROM outbox_events WHERE event_type = ?"
                                + " AND aggregate_id = ? ORDER BY created_at, id",
                        String.class,
                        AdminEventTypes.PLATFORM_STATS_COLLECTED_V1,
                        PlatformStatsCollectedEvent.aggregateId(bucket.toInstant()))
                .stream()
                .map(DomainEventEnvelopeJson::read)
                .toList();
    }

    private DomainEventEnvelope latestEvent(OffsetDateTime bucket) {
        List<DomainEventEnvelope> events = eventsFor(bucket);
        return events.get(events.size() - 1);
    }

    // Keyed "metric|dimension" so a test reads one figure without walking the row list.
    private static Map<String, Long> rowsOf(DomainEventEnvelope event) {
        return PlatformStatsCollectedEvent.parse(event.data()).rows().stream()
                .collect(
                        Collectors.toMap(
                                row -> row.metricKey() + "|" + row.dimension(),
                                PlatformStatsCollectedEvent.Row::value));
    }
}
