package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.comment.dto.response.CommentPreviewResponse;
import com.app.modules.comment.service.CommentPreviewService;
import com.app.testsupport.TestContainerImages;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class CommentPreviewVisibilityIT {

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
        r.add("JWT_SECRET", () -> "comment-preview-visibility-it-secret-32-chars!");
        r.add("JWT_ISSUER", () -> "https://comment-preview-visibility.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Preview IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private CommentPreviewService previewService;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void preview_viewerBlockedCommentAuthor_isUnavailable() {
        UUID viewer = user();
        UUID author = user();
        UUID comment = comment(post(author), author, null, "blocked words");
        block(viewer, author);

        CommentPreviewResponse preview =
                previewService.loadPreviews(viewer, List.of(comment)).get(comment);

        assertThat(preview.available()).isFalse();
        assertThat(preview.snippet()).isNull();
    }

    @Test
    void preview_commentAuthorBlockedViewer_isUnavailable() {
        UUID viewer = user();
        UUID author = user();
        UUID comment = comment(post(author), author, null, "blocked words");
        block(author, viewer);

        CommentPreviewResponse preview =
                previewService.loadPreviews(viewer, List.of(comment)).get(comment);

        assertThat(preview.available()).isFalse();
        assertThat(preview.snippet()).isNull();
    }

    @Test
    void preview_hiddenCommentForNonAuthor_isUnavailable() {
        UUID viewer = user();
        UUID author = user();
        UUID comment = comment(post(author), author, null, "hidden words");
        hide(comment);

        CommentPreviewResponse preview =
                previewService.loadPreviews(viewer, List.of(comment)).get(comment);

        assertThat(preview.available()).isFalse();
        assertThat(preview.snippet()).isNull();
    }

    @Test
    void preview_hiddenCommentForAuthor_isAvailable() {
        UUID author = user();
        UUID comment = comment(post(author), author, null, "my hidden words");
        hide(comment);

        CommentPreviewResponse preview =
                previewService.loadPreviews(author, List.of(comment)).get(comment);

        assertThat(preview.available()).isTrue();
        assertThat(preview.snippet()).isEqualTo("my hidden words");
    }

    @Test
    void preview_hiddenOrBlockedParent_hasNoParentSnippet() {
        UUID viewer = user();
        UUID parentAuthor = user();
        UUID replyAuthor = user();
        UUID post = post(replyAuthor);
        UUID hiddenParent = comment(post, parentAuthor, null, "hidden parent words");
        UUID blockedParent = comment(post, parentAuthor, null, "blocked parent words");
        UUID hiddenParentReply = comment(post, replyAuthor, hiddenParent, "reply one");
        UUID blockedParentReply = comment(post, replyAuthor, blockedParent, "reply two");
        hide(hiddenParent);

        CommentPreviewResponse hidden =
                previewService
                        .loadPreviews(viewer, List.of(hiddenParentReply))
                        .get(hiddenParentReply);
        assertThat(hidden.available()).isTrue();
        assertThat(hidden.parentSnippet()).isNull();

        jdbc.update(
                "UPDATE comments SET moderation_status = 'approved', hidden_reason = NULL WHERE"
                        + " id = ?",
                hiddenParent);
        block(viewer, parentAuthor);
        CommentPreviewResponse blocked =
                previewService
                        .loadPreviews(viewer, List.of(blockedParentReply))
                        .get(blockedParentReply);
        assertThat(blocked.available()).isTrue();
        assertThat(blocked.parentSnippet()).isNull();
    }

    private UUID user() {
        String name = "preview_" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject(
                "INSERT INTO users (username, email) VALUES (?, ?) RETURNING id",
                UUID.class,
                name,
                name + "@test.local");
    }

    private UUID post(UUID author) {
        return jdbc.queryForObject(
                "INSERT INTO posts (user_id, status) VALUES (?, 'published') RETURNING id",
                UUID.class,
                author);
    }

    private UUID comment(UUID post, UUID author, UUID parent, String content) {
        return jdbc.queryForObject(
                "INSERT INTO comments (post_id, user_id, parent_id, root_id, depth, content)"
                        + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                UUID.class,
                post,
                author,
                parent,
                parent,
                parent == null ? 0 : 1,
                content);
    }

    private void block(UUID blocker, UUID blocked) {
        jdbc.update("INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
    }

    private void hide(UUID comment) {
        jdbc.update(
                "UPDATE comments SET moderation_status = 'hidden', hidden_reason = 'hidden_words'"
                        + " WHERE id = ?",
                comment);
    }
}
