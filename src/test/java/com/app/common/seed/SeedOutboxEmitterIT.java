package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;
import com.app.common.seed.outbox.SeedOutboxEmitter;
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.CommentSeedWriter;
import com.app.common.seed.writer.EngagementSeedWriter;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.MessageSeedWriter;
import com.app.common.seed.writer.PostSeedWriter;
import com.app.common.seed.writer.StorySeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;

/**
 * Proves {@link SeedOutboxEmitter} against a real Flyway-migrated schema, chained after every
 * domain writer whose rows it reads back, the same way Task 8's {@code SeedRunner} will chain it
 * last.
 *
 * <p>This test only proves the {@code outbox_events} rows themselves are correctly shaped and
 * counted — it disables the outbox publisher and excludes RabbitMQ autoconfiguration, matching
 * every other seed writer IT's pattern. The live-stack drain through the real publisher and real
 * Elasticsearch/Gorse consumers (the mandatory envelope-correctness gate) was proven separately
 * against the local docker-compose stack; see the Task 9 report for that raw evidence.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class SeedOutboxEmitterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "seed-outbox-emitter-it-secret-32-characters!!");
        registry.add("JWT_ISSUER", () -> "https://seedoutboxemitter.it.local");
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
        registry.add("app.stats.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SeedResetService seedResetService;
    @Autowired private UserSeedWriter userSeedWriter;
    @Autowired private MediaSeedWriter mediaSeedWriter;
    @Autowired private StorySeedWriter storySeedWriter;
    @Autowired private MessageSeedWriter messageSeedWriter;
    @Autowired private PostSeedWriter postSeedWriter;
    @Autowired private CommentSeedWriter commentSeedWriter;
    @Autowired private EngagementSeedWriter engagementSeedWriter;
    @Autowired private SeedOutboxEmitter seedOutboxEmitter;

    @Test
    void emitProofSlice_writesExactlyOneEventOfEachKind() {
        seedDomainWriters();

        seedOutboxEmitter.emitProofSlice();

        Map<String, Integer> countByType = countOutboxEventsByType();
        assertThat(countByType.get("post.index.upsert.v1")).isEqualTo(1);
        assertThat(countByType.get("hashtag.index.upsert.v1")).isEqualTo(1);
        assertThat(countByType.get("post.liked.v1")).isEqualTo(1);
        assertThat(countByType.get("post.saved.v1")).isEqualTo(1);
        assertThat(countByType.get("comment.created.v1")).isEqualTo(1);
        assertThat(countByType.getOrDefault("post.viewed.v1", 0)).isZero();

        Integer total =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
        assertThat(total).isEqualTo(5);
    }

    @Test
    void emitFullVolume_writesOneEventPerRowAndEmitsSeededViews() {
        SeededIds seeded = seedDomainWriters();

        Integer allPostCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM posts", Integer.class);
        Integer hashtagCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM hashtags", Integer.class);
        Integer likeCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM post_likes", Integer.class);
        Integer saveCount =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM post_saves", Integer.class);
        Integer commentCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments WHERE deleted_at IS NULL", Integer.class);

        SeedOutboxEmitter.EmissionCounts counts =
                seedOutboxEmitter.emitFullVolume(
                        seeded.content(), seeded.usersByUsername(), seeded.postIdBySeedId());

        assertThat(counts.postIndex()).isEqualTo(allPostCount);
        assertThat(counts.hashtagIndex()).isEqualTo(hashtagCount);
        assertThat(counts.likes()).isEqualTo(likeCount);
        assertThat(counts.saves()).isEqualTo(saveCount);
        assertThat(counts.comments()).isEqualTo(commentCount);
        assertThat(counts.views()).isPositive();
        // Seeded post_share messages must produce share feedback, or "share" sits in
        // positive_feedback_types with an empty bucket behind it.
        assertThat(counts.shares()).isPositive();

        Map<String, Integer> countByType = countOutboxEventsByType();
        assertThat(countByType.get("post.index.upsert.v1")).isEqualTo(allPostCount);
        assertThat(countByType.get("hashtag.index.upsert.v1")).isEqualTo(hashtagCount);
        assertThat(countByType.get("post.liked.v1")).isEqualTo(likeCount);
        assertThat(countByType.get("post.saved.v1")).isEqualTo(saveCount);
        assertThat(countByType.get("comment.created.v1")).isEqualTo(commentCount);
        assertThat(countByType.get("post.viewed.v1")).isEqualTo(counts.views());
        assertThat(countByType.get("post.shared.v1")).isEqualTo(counts.shares());

        Integer total =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
        assertThat(total)
                .isEqualTo(
                        allPostCount
                                + hashtagCount
                                + likeCount
                                + saveCount
                                + commentCount
                                + counts.views()
                                + counts.shares());

        // Every row must still be PENDING: the outbox publisher is disabled in this test, so
        // nothing has drained yet - proves emission alone, not the live-stack drain.
        Integer pendingCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'",
                        Integer.class);
        assertThat(pendingCount).isEqualTo(total);

        // The replay must reach search indexing and recommendation feedback but never notify a
        // second time: every replayed like and comment is already in the inbox of the consumer
        // that would turn it into a notification, and nothing else is.
        assertThat(unhandledReplays("post.liked.v1", "post-notification-consumer")).isZero();
        assertThat(unhandledReplays("comment.created.v1", "comment-notification-consumer"))
                .isZero();
        Integer inboxRows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM processed_messages", Integer.class);
        assertThat(inboxRows).isEqualTo(likeCount + commentCount);
    }

    private int unhandledReplays(String eventType, String consumerName) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM outbox_events o WHERE o.event_type = ?"
                                + " AND NOT EXISTS (SELECT 1 FROM processed_messages pm"
                                + " WHERE pm.consumer_name = ? AND pm.event_id = o.event_id)",
                        Integer.class,
                        eventType,
                        consumerName);
        return count == null ? 0 : count;
    }

    @Test
    void emitFullVolume_viewSetCoversEveryLikeAndSavePair() {
        SeededIds seeded = seedDomainWriters();

        seedOutboxEmitter.emitFullVolume(
                seeded.content(), seeded.usersByUsername(), seeded.postIdBySeedId());

        // Reading precedes liking, so no like or save pair may be missing from the view set.
        // Self-reactions are excluded: PostViewServiceImpl never emits an event for a post owner
        // viewing their own post, so the seeded view set deliberately cannot cover those pairs.
        Integer uncovered =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ("
                                + " SELECT user_id, post_id FROM post_likes"
                                + " UNION SELECT user_id, post_id FROM post_saves) r"
                                + " JOIN posts p ON p.id = r.post_id"
                                + " WHERE p.user_id <> r.user_id"
                                + " AND NOT EXISTS ("
                                + "   SELECT 1 FROM outbox_events e"
                                + "   WHERE e.event_type = 'post.viewed.v1'"
                                + "     AND e.aggregate_id = r.post_id"
                                + "     AND e.payload ->> 'actorId' = r.user_id::text)",
                        Integer.class);
        assertThat(uncovered).isZero();
    }

    @Test
    void emitFullVolume_viewSetIsWeightedByEngagementBand() {
        SeededIds seeded = seedDomainWriters();

        seedOutboxEmitter.emitFullVolume(
                seeded.content(), seeded.usersByUsername(), seeded.postIdBySeedId());

        // A viral-band post must attract more views than a low-band one, or the trending score's
        // numerator carries no information.
        double viralAverage = averageViewsForBand(seeded, "viral");
        double lowAverage = averageViewsForBand(seeded, "low");
        assertThat(viralAverage).isGreaterThan(lowAverage);
    }

    private double averageViewsForBand(SeededIds seeded, String band) {
        List<UUID> postIds =
                seeded.content().posts().stream()
                        .filter(post -> "published".equals(post.status()))
                        .filter(post -> band.equals(post.engagementBand()))
                        .map(post -> seeded.postIdBySeedId().get(post.id()))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        if (postIds.isEmpty()) {
            return 0.0;
        }
        int total = 0;
        for (UUID postId : postIds) {
            Integer count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                                    + " 'post.viewed.v1' AND aggregate_id = ?",
                            Integer.class,
                            postId);
            total += count == null ? 0 : count;
        }
        return (double) total / postIds.size();
    }

    @Test
    void emitPostIndexPayload_matchesPostIndexUpsertEventShape() {
        seedDomainWriters();

        seedOutboxEmitter.emitProofSlice();

        String payloadJson =
                jdbcTemplate.queryForObject(
                        "SELECT payload::text FROM outbox_events WHERE event_type ="
                                + " 'post.index.upsert.v1'",
                        String.class);
        assertThat(payloadJson).contains("\"postId\"");
        assertThat(payloadJson).contains("\"userId\"");
        assertThat(payloadJson).contains("\"hashtagIds\"");
        assertThat(payloadJson).contains("\"createdAt\"");
    }

    private record SeededIds(
            SeedContent content,
            Map<String, UUID> usersByUsername,
            Map<String, UUID> postIdBySeedId) {}

    private SeededIds seedDomainWriters() {
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
        // Messages are seeded here purely so post_share rows exist: they are what the emitter
        // reads back to produce post.shared.v1, and without them the share bucket is empty.
        // StorySeedWriter must precede it: MessageSeedWriter fails outright on a seeded
        // story_share whose referenced story does not exist yet.
        storySeedWriter.write(content, usersByUsername, timeline);
        messageSeedWriter.write(
                content, usersByUsername, mediaByCompositeKey, postIdBySeedId, timeline);
        return new SeededIds(content, usersByUsername, postIdBySeedId);
    }

    private Map<String, Integer> countOutboxEventsByType() {
        return jdbcTemplate
                .query(
                        "SELECT event_type, COUNT(*) AS cnt FROM outbox_events GROUP BY event_type",
                        (rs, rowNum) -> Map.entry(rs.getString("event_type"), rs.getInt("cnt")))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
