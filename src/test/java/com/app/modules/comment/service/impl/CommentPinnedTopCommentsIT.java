package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import com.app.common.response.CursorPageResponse;
import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.service.CommentService;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Covers the pinned top-comments block end to end against real PostgreSQL: which comments are
 * pinned, that the first page never repeats one in its newest-first body, that page two is
 * untouched by the feature, and that the block costs a constant number of statements.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "app.comment.consumer.enabled=false",
            "app.comment.live.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class CommentPinnedTopCommentsIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "comment-pinned-top-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://comment-pinned-top.it.local");
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

    @MockitoBean private MailService mailService;

    @Autowired private CommentService commentService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private int likerSequence;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM comment_likes");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void firstPage_threeEligible_pinsExactlyThoseThreeInLikeCountOrder() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID top = insertTopLevelComment(post, owner, minutesAgo(5));
        UUID middle = insertTopLevelComment(post, owner, minutesAgo(4));
        UUID bottom = insertTopLevelComment(post, owner, minutesAgo(3));
        for (int i = 0; i < 6; i++) {
            insertTopLevelComment(post, owner, minutesAgo(i));
        }
        likeNTimes(top, 3);
        likeNTimes(middle, 2);
        likeNTimes(bottom, 1);

        List<CommentResponse> content = list(viewer, post, null, 10).getContent();

        assertThat(content.subList(0, 3))
                .extracting(CommentResponse::id)
                .containsExactly(top, middle, bottom);
        assertThat(content.subList(0, 3)).allSatisfy(c -> assertThat(c.pinned()).isTrue());
    }

    @Test
    void firstPage_twoEligible_pinsTwo() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID first = insertTopLevelComment(post, owner, minutesAgo(5));
        UUID second = insertTopLevelComment(post, owner, minutesAgo(4));
        insertTopLevelComment(post, owner, minutesAgo(3));
        likeNTimes(first, 2);
        likeNTimes(second, 1);

        List<CommentResponse> content = list(viewer, post, null, 10).getContent();

        assertThat(content.stream().filter(CommentResponse::pinned).map(CommentResponse::id))
                .containsExactly(first, second);
    }

    @Test
    void firstPage_oneEligible_pinsOne() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID only = insertTopLevelComment(post, owner, minutesAgo(5));
        insertTopLevelComment(post, owner, minutesAgo(4));
        likeNTimes(only, 1);

        List<CommentResponse> content = list(viewer, post, null, 10).getContent();

        assertThat(content.stream().filter(CommentResponse::pinned).map(CommentResponse::id))
                .containsExactly(only);
    }

    @Test
    void firstPage_noLikesAnywhere_pinsNothing() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        for (int i = 0; i < 4; i++) {
            insertTopLevelComment(post, owner, minutesAgo(i));
        }

        List<CommentResponse> content = list(viewer, post, null, 10).getContent();

        assertThat(content).hasSize(4);
        assertThat(content).noneMatch(CommentResponse::pinned);
    }

    @Test
    void firstPage_zeroLikeComment_isNeverPinned() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID liked = insertTopLevelComment(post, owner, minutesAgo(5));
        UUID unliked = insertTopLevelComment(post, owner, minutesAgo(4));
        likeNTimes(liked, 1);

        List<CommentResponse> content = list(viewer, post, null, 10).getContent();

        assertThat(content.stream().filter(CommentResponse::pinned).map(CommentResponse::id))
                .containsExactly(liked);
        assertThat(content.stream().filter(c -> c.id().equals(unliked)))
                .allSatisfy(c -> assertThat(c.pinned()).isFalse());
    }

    @Test
    void firstPage_pinnedComment_doesNotAlsoAppearInTheBody() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID popular = insertTopLevelComment(post, owner, minutesAgo(5));
        for (int i = 0; i < 4; i++) {
            insertTopLevelComment(post, owner, minutesAgo(i));
        }
        likeNTimes(popular, 4);

        List<CommentResponse> content = list(viewer, post, null, 20).getContent();

        assertThat(content).extracting(CommentResponse::id).doesNotHaveDuplicates();
        assertThat(content.stream().filter(c -> c.id().equals(popular))).hasSize(1);
        assertThat(content).hasSize(5);
    }

    @Test
    void firstPage_softDeletedAndModerationRemovedComments_areNeverPinned() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID eligible = insertTopLevelComment(post, owner, minutesAgo(5));
        UUID softDeleted = insertTopLevelComment(post, owner, minutesAgo(4));
        UUID removed = insertTopLevelComment(post, owner, minutesAgo(3));
        likeNTimes(eligible, 1);
        likeNTimes(softDeleted, 50);
        likeNTimes(removed, 50);
        jdbcTemplate.update("UPDATE comments SET deleted_at = NOW() WHERE id = ?", softDeleted);
        jdbcTemplate.update(
                "UPDATE comments SET moderation_status = 'removed' WHERE id = ?", removed);

        List<CommentResponse> content = list(viewer, post, null, 10).getContent();

        assertThat(content.stream().filter(CommentResponse::pinned).map(CommentResponse::id))
                .containsExactly(eligible);
        assertThat(content).extracting(CommentResponse::id).doesNotContain(softDeleted, removed);
    }

    @Test
    void firstPage_aReply_isNeverPinnedIntoTheTopLevelList() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID parent = insertTopLevelComment(post, owner, minutesAgo(5));
        UUID reply = insertReply(post, owner, parent, minutesAgo(4));
        likeNTimes(reply, 50);
        likeNTimes(parent, 1);

        List<CommentResponse> content = list(viewer, post, null, 10).getContent();

        assertThat(content).extracting(CommentResponse::id).doesNotContain(reply);
        assertThat(content.stream().filter(CommentResponse::pinned).map(CommentResponse::id))
                .containsExactly(parent);
    }

    @Test
    void secondPage_carriesNoPinnedBlockAndMarksNoRowPinned() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID popular = insertTopLevelComment(post, owner, minutesAgo(20));
        for (int i = 0; i < 10; i++) {
            insertTopLevelComment(post, owner, minutesAgo(i));
        }
        likeNTimes(popular, 5);

        CursorPageResponse<CommentResponse> first = list(viewer, post, null, 4);
        List<CommentResponse> second =
                list(viewer, post, first.getPageInfo().getEndCursor(), 4).getContent();

        assertThat(second).noneMatch(CommentResponse::pinned);
        assertThat(second).hasSize(4);
        assertThat(second)
                .extracting(CommentResponse::id)
                .doesNotContainAnyElementsOf(
                        first.getContent().stream().map(CommentResponse::id).toList());
    }

    @Test
    void firstPage_bodyIsAFullPageOfTheRequestedLimitWithPinnedOnTop() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID popular = insertTopLevelComment(post, owner, minutesAgo(30));
        for (int i = 0; i < 10; i++) {
            insertTopLevelComment(post, owner, minutesAgo(i));
        }
        likeNTimes(popular, 5);

        List<CommentResponse> content = list(viewer, post, null, 4).getContent();

        // Pinned rows are additional to the requested limit, so the body is still four rows.
        assertThat(content).hasSize(5);
        assertThat(content.stream().filter(CommentResponse::pinned)).hasSize(1);
    }

    @Test
    void firstPage_queryCountIsConstantAcrossThreePageSizes() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        for (int i = 0; i < 14; i++) {
            UUID comment = insertTopLevelComment(post, owner, minutesAgo(i));
            if (i % 4 == 0) {
                likeNTimes(comment, i + 1);
            }
        }

        Statistics stats = statistics();
        list(viewer, post, null, 2);

        stats.clear();
        list(viewer, post, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        list(viewer, post, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        list(viewer, post, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void laterPage_issuesTheSameStatementCountAsTheFirstPage() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        for (int i = 0; i < 14; i++) {
            UUID comment = insertTopLevelComment(post, owner, minutesAgo(i));
            if (i % 4 == 0) {
                likeNTimes(comment, i + 1);
            }
        }

        Statistics stats = statistics();
        CursorPageResponse<CommentResponse> first = list(viewer, post, null, 4);
        String cursor = first.getPageInfo().getEndCursor();
        list(viewer, post, cursor, 4);

        stats.clear();
        list(viewer, post, null, 4);
        long firstPage = stats.getPrepareStatementCount();

        stats.clear();
        list(viewer, post, cursor, 4);
        long laterPage = stats.getPrepareStatementCount();

        // Every page issues the same nine statements: the read path (post lookup, visibility
        // gate, body query, author batch, viewer-like batch, viewer-reported batch) plus the
        // pinned ranking. A later page runs the ranking too, not to prepend a block but to obtain
        // the ids it must exclude from its body; without that a pinned comment old enough to land
        // on this page would be returned a second time. Absolute counts are pinned, not just the
        // relationship between them, so an extra round trip anywhere on this path fails here.
        assertThat(firstPage).isEqualTo(9);
        assertThat(laterPage).isEqualTo(9);
    }

    private CursorPageResponse<CommentResponse> list(
            UUID viewer, UUID post, String cursor, int limit) {
        return commentService.listTopLevelComments(viewer, post, null, cursor, limit);
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
    }

    // Drives like_count through the production trigger on comment_likes rather than writing the
    // counter directly, so the ranking is exercised against the same signal the application uses.
    private void likeNTimes(UUID commentId, int likes) {
        for (int i = 0; i < likes; i++) {
            // Usernames are capped at 30 characters, so the liker name is a short running counter.
            UUID liker = insertUser("liker" + likerSequence++);
            jdbcTemplate.update(
                    "INSERT INTO comment_likes(comment_id, user_id) VALUES (?, ?)",
                    commentId,
                    liker);
        }
    }

    private UUID insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username);
    }

    private UUID insertPublicPost(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }

    private UUID insertTopLevelComment(UUID postId, UUID userId, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO comments(post_id, user_id, depth, content, moderation_status,"
                        + " created_at) VALUES (?, ?, 0, 'text', 'approved', ?) RETURNING id",
                UUID.class,
                postId,
                userId,
                createdAt);
    }

    private UUID insertReply(UUID postId, UUID userId, UUID parentId, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO comments(post_id, user_id, parent_id, root_id, depth, content,"
                        + " moderation_status, created_at) VALUES (?, ?, ?, ?, 1, 'text',"
                        + " 'approved', ?) RETURNING id",
                UUID.class,
                postId,
                userId,
                parentId,
                parentId,
                createdAt);
    }
}
