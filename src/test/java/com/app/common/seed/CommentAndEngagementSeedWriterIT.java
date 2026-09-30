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
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.CommentSeedWriter;
import com.app.common.seed.writer.EngagementSeedWriter;
import com.app.common.seed.writer.MediaSeedWriter;
import com.app.common.seed.writer.PostSeedWriter;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves {@link CommentSeedWriter} and {@link EngagementSeedWriter} against a real Flyway-migrated
 * schema, chained after {@link UserSeedWriter}, {@link MediaSeedWriter} and {@link PostSeedWriter}
 * the same way {@code SeedRunner} chains them.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class CommentAndEngagementSeedWriterIT {

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
        registry.add("JWT_SECRET", () -> "comment-engagement-writer-it-secret-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://commentengagementwriter.it.local");
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
    @Autowired private CommentSeedWriter commentSeedWriter;
    @Autowired private EngagementSeedWriter engagementSeedWriter;

    @Test
    void write_seedsCommentsAndEngagementAgainstRealSchema() {
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

        // comments: every generated id must be a real, distinct row.
        assertThat(commentIds).isNotEmpty();
        assertThat(commentIds).doesNotHaveDuplicates();
        assertThat(countRows("comments")).isEqualTo(commentIds.size());

        // depth CHECK constraint respected by construction; never negative, never past 10.
        Integer outOfRangeDepth =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments WHERE depth < 0 OR depth > 10",
                        Integer.class);
        assertThat(outOfRangeDepth).isZero();

        // Adjacency-list invariants: every root comment self-references root_id; every reply's
        // parent_id resolves to a real comment row.
        Integer rootsNotSelfReferencing =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments WHERE parent_id IS NULL AND root_id <> id",
                        Integer.class);
        assertThat(rootsNotSelfReferencing).isZero();
        Integer danglingParents =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments c WHERE c.parent_id IS NOT NULL AND NOT"
                                + " EXISTS (SELECT 1 FROM comments p WHERE p.id = c.parent_id)",
                        Integer.class);
        assertThat(danglingParents).isZero();

        // Ordering invariants: a comment never precedes its post; a reply never precedes its
        // parent.
        Integer commentsBeforePost =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments c JOIN posts p ON p.id = c.post_id WHERE"
                                + " c.created_at <= p.created_at",
                        Integer.class);
        assertThat(commentsBeforePost).isZero();
        Integer repliesBeforeParent =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments c JOIN comments parent ON parent.id ="
                                + " c.parent_id WHERE c.created_at <= parent.created_at",
                        Integer.class);
        assertThat(repliesBeforeParent).isZero();

        // At least 10 soft-deleted subtrees: every comment sharing a soft-deleted root's root_id
        // must itself be soft-deleted, and there must be at least 10 distinct soft-deleted roots.
        Integer softDeletedRoots =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments WHERE parent_id IS NULL AND deleted_at IS"
                                + " NOT NULL",
                        Integer.class);
        assertThat(softDeletedRoots).isGreaterThanOrEqualTo(10);
        Integer subtreeMembersNotDeleted =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments c JOIN comments root ON root.id = c.root_id"
                                + " WHERE root.deleted_at IS NOT NULL AND c.deleted_at IS NULL",
                        Integer.class);
        assertThat(subtreeMembersNotDeleted).isZero();

        // Engagement: batched, deduped, ON CONFLICT-safe (compound PK never violated).
        assertThat(countRows("post_likes")).isGreaterThan(10_000);
        assertThat(countRows("post_saves")).isGreaterThan(1_000);
        assertThat(countRows("comment_likes")).isGreaterThan(2_000);

        Integer danglingPostLikes =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM post_likes pl LEFT JOIN posts p ON p.id = pl.post_id"
                                + " WHERE p.id IS NULL",
                        Integer.class);
        assertThat(danglingPostLikes).isZero();
        Integer likesBeforePost =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM post_likes pl JOIN posts p ON p.id = pl.post_id"
                                + " WHERE pl.created_at <= p.created_at",
                        Integer.class);
        assertThat(likesBeforePost).isZero();
        Integer savesBeforePost =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM post_saves ps JOIN posts p ON p.id = ps.post_id"
                                + " WHERE ps.created_at <= p.created_at",
                        Integer.class);
        assertThat(savesBeforePost).isZero();
        Integer commentLikesOnDeletedComments =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comment_likes cl JOIN comments c ON c.id ="
                                + " cl.comment_id WHERE c.deleted_at IS NOT NULL",
                        Integer.class);
        assertThat(commentLikesOnDeletedComments).isZero();

        // like_count/comment_count/save_count are trigger-maintained; verify triggers actually
        // fired for every affected post/comment now that likes/saves/comments exist.
        Integer postCounterMismatches =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM posts p WHERE p.like_count <> (SELECT COUNT(*) FROM"
                                + " post_likes pl WHERE pl.post_id = p.id) OR p.save_count <>"
                                + " (SELECT COUNT(*) FROM post_saves ps WHERE ps.post_id = p.id)"
                                + " OR p.comment_count <> (SELECT COUNT(*) FROM comments c WHERE"
                                + " c.post_id = p.id AND c.deleted_at IS NULL)",
                        Integer.class);
        assertThat(postCounterMismatches).isZero();

        // comments.like_count/reply_count are trigger-maintained; verify the trigger tracks
        // comment_likes and non-deleted child replies rather than this writer setting them.
        Integer commentCounterMismatches =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM comments c WHERE c.like_count <> (SELECT COUNT(*)"
                                + " FROM comment_likes cl WHERE cl.comment_id = c.id) OR"
                                + " c.reply_count <> (SELECT COUNT(*) FROM comments r WHERE"
                                + " r.parent_id = c.id AND r.deleted_at IS NULL)",
                        Integer.class);
        assertThat(commentCounterMismatches).isZero();
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
