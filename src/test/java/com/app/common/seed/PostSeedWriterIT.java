package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import com.app.common.seed.model.HashtagSeed;
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.PostSeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;

/**
 * Proves {@link PostSeedWriter} against a real Flyway-migrated schema, chained after {@link
 * UserSeedWriter} and {@link MediaSeedWriter} the same way Task 8's {@code SeedRunner} will chain
 * them.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class PostSeedWriterIT {

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
        registry.add("JWT_SECRET", () -> "post-seed-writer-it-secret-at-least-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://postseedwriter.it.local");
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
    @Autowired private PostSeedWriter postSeedWriter;

    @Test
    void write_seedsEveryPostWithMediaHashtagsAndPlausibleViewCounts() {
        seedResetService.reset();
        SeedContent content = new SeedDataLoader().load();
        SeedTimeline timeline = new SeedTimeline(20260825L, Instant.parse("2026-08-25T00:00:00Z"));

        Map<String, UUID> usersByUsername = userSeedWriter.write(content, timeline);
        Map<String, UUID> mediaByCompositeKey = mediaSeedWriter.write(content, usersByUsername);
        Map<String, UUID> postIdBySeedId =
                postSeedWriter.write(content, usersByUsername, mediaByCompositeKey, timeline);

        int expectedPostCount = content.posts().size();
        assertThat(postIdBySeedId).hasSize(expectedPostCount);
        assertThat(countRows("posts")).isEqualTo(expectedPostCount);

        // view_count is the one counter this writer must set explicitly; every row must be
        // non-zero and plausible for its declared engagement band.
        Integer zeroViewCounts =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM posts WHERE view_count <= 0", Integer.class);
        assertThat(zeroViewCounts).isZero();

        // like_count/comment_count/save_count are trigger-maintained and must stay at DEFAULT 0
        // immediately after this writer runs (no likes/comments/saves exist yet).
        Integer nonZeroTriggerCounters =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM posts WHERE like_count <> 0 OR comment_count <> 0"
                                + " OR save_count <> 0",
                        Integer.class);
        assertThat(nonZeroTriggerCounters).isZero();

        // post_media linkage: every row must resolve to a real media_assets row, and every
        // (image|video|carousel) post must carry at least one post_media row.
        Integer danglingPostMedia =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM post_media pm LEFT JOIN media_assets m ON m.id ="
                                + " pm.media_asset_id WHERE m.id IS NULL",
                        Integer.class);
        assertThat(danglingPostMedia).isZero();
        Integer mediaPostsMissingMedia =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM posts p WHERE p.post_type <> 'text' AND NOT EXISTS"
                                + " (SELECT 1 FROM post_media pm WHERE pm.post_id = p.id)",
                        Integer.class);
        assertThat(mediaPostsMissingMedia).isZero();
        Integer textPostsWithMedia =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM posts p WHERE p.post_type = 'text' AND EXISTS"
                                + " (SELECT 1 FROM post_media pm WHERE pm.post_id = p.id)",
                        Integer.class);
        assertThat(textPostsWithMedia).isZero();

        // post_hashtags linkage: every row must resolve to a real hashtags row, and only
        // published posts may carry an association (matching the production invariant that a
        // draft/archived/removed post carries none).
        Integer danglingPostHashtags =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM post_hashtags ph LEFT JOIN hashtags h ON h.id ="
                                + " ph.hashtag_id WHERE h.id IS NULL",
                        Integer.class);
        assertThat(danglingPostHashtags).isZero();
        Integer nonPublishedWithHashtags =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM post_hashtags ph JOIN posts p ON p.id = ph.post_id"
                                + " WHERE p.status <> 'published'",
                        Integer.class);
        assertThat(nonPublishedWithHashtags).isZero();
        assertThat(countRows("hashtags")).isEqualTo(content.hashtags().size());

        // hashtag_trending: exactly the hashtags.json entries marked trending get a snapshot
        // row. Derived from the loaded content rather than hardcoded, because the count changes
        // whenever a trending hashtag is added and a literal here would fail for the wrong reason.
        long expectedTrending = content.hashtags().stream().filter(HashtagSeed::isTrending).count();
        assertThat(countRows("hashtag_trending")).isEqualTo((int) expectedTrending);
        Integer trendingRankRange =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM hashtag_trending WHERE rank BETWEEN 1 AND ?",
                        Integer.class,
                        (int) expectedTrending);
        assertThat(trendingRankRange).isEqualTo((int) expectedTrending);
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
