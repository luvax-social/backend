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

import com.app.modules.comment.dto.response.CommentResponse;
import com.app.modules.comment.service.CommentService;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the viewer's comment-like state is resolved with a constant query count across page size,
 * and that the flag is correct per row rather than uniformly set.
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
class CommentViewerStateIT {

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
        r.add("JWT_SECRET", () -> "comment-viewer-state-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://comment-viewer-state.it.local");
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

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM comment_likes");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void listTopLevelComments_viewerStateQueryCountIsConstantAcrossThreePageSizes() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        for (int i = 0; i < 12; i++) {
            UUID comment = insertTopLevelComment(post, owner, minutesAgo(i));
            if (i % 2 == 0) {
                likeComment(comment, viewer);
            }
        }

        Statistics stats = statistics();
        commentService.listTopLevelComments(viewer, post, null, null, 2);

        stats.clear();
        commentService.listTopLevelComments(viewer, post, null, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        commentService.listTopLevelComments(viewer, post, null, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        commentService.listTopLevelComments(viewer, post, null, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void listReplies_viewerStateQueryCountIsConstantAcrossThreePageSizes() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID parent = insertTopLevelComment(post, owner, minutesAgo(20));
        for (int i = 0; i < 12; i++) {
            UUID reply = insertReply(post, owner, parent, minutesAgo(i));
            if (i % 3 == 0) {
                likeComment(reply, viewer);
            }
        }

        Statistics stats = statistics();
        commentService.listReplies(viewer, parent, null, 2);

        stats.clear();
        commentService.listReplies(viewer, parent, null, 2);
        long size2 = stats.getPrepareStatementCount();

        stats.clear();
        commentService.listReplies(viewer, parent, null, 6);
        long size6 = stats.getPrepareStatementCount();

        stats.clear();
        commentService.listReplies(viewer, parent, null, 12);
        long size12 = stats.getPrepareStatementCount();

        assertThat(size6).isEqualTo(size2);
        assertThat(size12).isEqualTo(size2);
    }

    @Test
    void listTopLevelComments_mixedLikedAndUnliked_flagsCorrectPerRowNotUniform() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID likedComment = insertTopLevelComment(post, owner, minutesAgo(2));
        UUID unlikedComment = insertTopLevelComment(post, owner, minutesAgo(1));
        likeComment(likedComment, viewer);

        List<CommentResponse> content =
                commentService.listTopLevelComments(viewer, post, null, null, 10).getContent();

        assertThat(content).hasSize(2);
        CommentResponse likedResponse =
                content.stream().filter(c -> c.id().equals(likedComment)).findFirst().orElseThrow();
        CommentResponse unlikedResponse =
                content.stream()
                        .filter(c -> c.id().equals(unlikedComment))
                        .findFirst()
                        .orElseThrow();
        assertThat(likedResponse.isLiked()).isTrue();
        assertThat(unlikedResponse.isLiked()).isFalse();
    }

    @Test
    void listReplies_mixedLikedAndUnliked_flagsCorrectPerRowNotUniform() {
        UUID owner = insertUser("owner");
        UUID viewer = insertUser("viewer");
        UUID post = insertPublicPost(owner);
        UUID parent = insertTopLevelComment(post, owner, minutesAgo(10));
        UUID likedReply = insertReply(post, owner, parent, minutesAgo(2));
        UUID unlikedReply = insertReply(post, owner, parent, minutesAgo(1));
        likeComment(likedReply, viewer);

        List<CommentResponse> content =
                commentService.listReplies(viewer, parent, null, 10).getContent();

        assertThat(content).hasSize(2);
        CommentResponse likedResponse =
                content.stream().filter(c -> c.id().equals(likedReply)).findFirst().orElseThrow();
        CommentResponse unlikedResponse =
                content.stream().filter(c -> c.id().equals(unlikedReply)).findFirst().orElseThrow();
        assertThat(likedResponse.isLiked()).isTrue();
        assertThat(unlikedResponse.isLiked()).isFalse();
    }

    private OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
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

    private void likeComment(UUID commentId, UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO comment_likes(comment_id, user_id) VALUES (?, ?)", commentId, userId);
    }
}
