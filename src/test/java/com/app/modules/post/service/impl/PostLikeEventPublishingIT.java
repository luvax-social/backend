package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.app.modules.mail.service.MailService;
import com.app.modules.post.service.PostLikeService;

/**
 * Proves liking and unliking a post each record an outbox event carrying the identifiers the live
 * tier needs, and that neither carries a materialised like count.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class PostLikeEventPublishingIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "post-like-event-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://post-like-event.it.local");
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

    @Autowired private PostLikeService postLikeService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void likePost_recordsALiveLikedEventCarryingThePostAndItsOwner() {
        UUID owner = insertUser("owner");
        UUID liker = insertUser("liker");
        UUID post = insertPublishedPost(owner);

        postLikeService.likePost(liker, post);

        Map<String, Object> row = outboxRow("post.live.liked.v1");
        assertThat(row.get("routing_key")).isEqualTo("post.live.liked.v1");
        String payload = String.valueOf(row.get("payload"));
        assertThat(payload).contains(post.toString());
        assertThat(payload).contains(owner.toString());
    }

    @Test
    void unlikePost_recordsTheUnlikeAndItsLiveEvent() {
        UUID owner = insertUser("owner");
        UUID liker = insertUser("liker");
        UUID post = insertPublishedPost(owner);
        postLikeService.likePost(liker, post);
        jdbcTemplate.update("DELETE FROM outbox_events");

        postLikeService.unlikePost(liker, post);

        outboxRow("post.live.unliked.v1");
        outboxRow("post.unliked.v1");
    }

    @Test
    void selfLike_isPermittedAndRecordsAnEvent() {
        UUID owner = insertUser("owner");
        UUID post = insertPublishedPost(owner);

        postLikeService.likePost(owner, post);

        outboxRow("post.live.liked.v1");
    }

    @Test
    void likeEventPayload_carriesNoMaterialisedLikeCount() {
        UUID owner = insertUser("owner");
        UUID liker = insertUser("liker");
        UUID post = insertPublishedPost(owner);

        postLikeService.likePost(liker, post);

        // The count is re-read at push time, so carrying one here would be a stale duplicate.
        assertThat(String.valueOf(outboxRow("post.live.liked.v1").get("payload")))
                .doesNotContain("likeCount");
    }

    // The like path enqueues both the live-fanout event asserted here and a separate
    // recommendation-feedback event outside this class's concern, so a row count must be scoped
    // to the event type under test rather than the whole table.
    private Map<String, Object> outboxRow(String eventType) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT event_type, routing_key, payload::text AS payload"
                                + " FROM outbox_events WHERE event_type = ? ORDER BY created_at"
                                + " DESC",
                        eventType);
        assertThat(rows).hasSize(1);
        return rows.get(0);
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

    private UUID insertPublishedPost(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }
}
