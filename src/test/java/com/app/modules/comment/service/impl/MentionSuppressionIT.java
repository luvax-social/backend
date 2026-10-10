package com.app.modules.comment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.ObjectMapper;

/**
 * Checks which mentioned accounts survive into {@code comment.created.v1}: an account the policy
 * hides the actor from, or blocks, is dropped so it is never notified, and a mention the policy
 * allows is kept.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.comment.consumer.enabled=false",
            "app.comment.live.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class MentionSuppressionIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "mention-suppression-it-secret-32-chars-min!!!");
        r.add("JWT_ISSUER", () -> "https://mention.it.local");
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

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM comment_write_idempotency");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM interaction_limits");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM oauth_accounts");
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void mention_accountLimitingNonFollowers_isExcludedWhileOthersRemain() {
        TestUser owner = registerUser("ms_limit_owner");
        TestUser actor = registerUser("ms_limit_actor");
        TestUser limited = registerUser("ms_limited");
        TestUser open = registerUser("ms_open");
        UUID postId = createImagePost(owner, "mention post");
        limitInteractions(limited, true, false);

        UUID commentId = comment(actor, postId, "hello @ms_limited and @ms_open");

        assertThat(mentionedUserIds(commentId)).containsExactly(open.id().toString());
    }

    @Test
    void mention_accountLimitingNonFollowers_isKeptForAnEstablishedFollower() {
        TestUser owner = registerUser("ms_fol_owner");
        TestUser actor = registerUser("ms_fol_actor");
        TestUser limited = registerUser("ms_fol_limited");
        UUID postId = createImagePost(owner, "follower mention post");
        limitInteractions(limited, true, false);
        followLongAgo(actor, limited);

        UUID commentId = comment(actor, postId, "hello @ms_fol_limited");

        assertThat(mentionedUserIds(commentId)).containsExactly(limited.id().toString());
    }

    @Test
    void mention_accountLimitingRecentFollowers_isExcludedForANewFollower() {
        TestUser owner = registerUser("ms_new_owner");
        TestUser actor = registerUser("ms_new_actor");
        TestUser limited = registerUser("ms_new_limited");
        UUID postId = createImagePost(owner, "new follower mention post");
        limitInteractions(limited, false, true);
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, following_id) VALUES (?, ?)",
                actor.id(),
                limited.id());

        UUID commentId = comment(actor, postId, "hello @ms_new_limited");

        assertThat(mentionedUserIds(commentId)).isEmpty();
    }

    @Test
    void mention_blockedAccount_isExcluded() {
        TestUser owner = registerUser("ms_blk_owner");
        TestUser actor = registerUser("ms_blk_actor");
        TestUser blocked = registerUser("ms_blk_target");
        UUID postId = createImagePost(owner, "blocked mention post");
        jdbcTemplate.update(
                "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)",
                blocked.id(),
                actor.id());

        UUID commentId = comment(actor, postId, "hello @ms_blk_target");

        assertThat(mentionedUserIds(commentId)).isEmpty();
    }

    @Test
    void mention_ownerOfTheLimitedPost_isStillMentionableWhenNotLimited() {
        TestUser owner = registerUser("ms_plain_owner");
        TestUser actor = registerUser("ms_plain_actor");
        UUID postId = createImagePost(owner, "plain mention post");

        UUID commentId = comment(actor, postId, "thanks @ms_plain_owner");

        assertThat(mentionedUserIds(commentId)).containsExactly(owner.id().toString());
    }

    private UUID comment(TestUser user, UUID postId, String content) {
        Map<String, Object> body = new HashMap<>();
        body.put("postId", postId.toString());
        body.put("content", content);
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + postId + "/comments",
                        HttpMethod.POST,
                        new HttpEntity<>(body, authHeaders(user)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    @SuppressWarnings("unchecked")
    private List<String> mentionedUserIds(UUID commentId) {
        String json =
                jdbcTemplate.queryForObject(
                        "SELECT payload->'data'->'mentionedUserIds' FROM outbox_events WHERE"
                                + " aggregate_id = ? AND event_type = 'comment.created.v1'",
                        String.class,
                        commentId);
        return objectMapper.readValue(json, List.class);
    }

    private void limitInteractions(TestUser owner, boolean nonFollowers, boolean recentFollowers) {
        jdbcTemplate.update(
                "INSERT INTO interaction_limits (user_id, limit_non_followers,"
                        + " limit_recent_followers, expires_at) VALUES (?, ?, ?, NOW() + INTERVAL"
                        + " '7 days')",
                owner.id(),
                nonFollowers,
                recentFollowers);
    }

    private void followLongAgo(TestUser follower, TestUser followed) {
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, following_id) VALUES (?, ?)",
                follower.id(),
                followed.id());
        jdbcTemplate.update(
                "UPDATE follows SET accepted_at = NOW() - INTERVAL '30 days' WHERE follower_id ="
                        + " ? AND following_id = ?",
                follower.id(),
                followed.id());
    }

    private TestUser registerUser(String username) {
        String email = username + "@test.local";
        String password = "S3cur3P@ssword!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());

        Map<String, Object> registerPayload =
                Map.of(
                        "username", username,
                        "email", email,
                        "password", password,
                        "displayName", username);
        ResponseEntity<Map> reg =
                rest.exchange(
                        "/api/v1/auth/register",
                        HttpMethod.POST,
                        new HttpEntity<>(registerPayload, headers),
                        Map.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = ?", UUID.class, username);
        jdbcTemplate.update(
                "UPDATE user_credentials SET email_verified = TRUE WHERE user_id = ?", id);

        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        return new TestUser(id, (String) data.get("accessToken"));
    }

    private UUID createImagePost(TestUser author, String caption) {
        UUID mediaId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size) VALUES (?, ?, ?, ?, CAST(? AS media_type), ?, ?)",
                mediaId,
                author.id(),
                "test/" + mediaId,
                "https://cdn.test/" + mediaId,
                "image",
                "image/jpeg",
                1024L);
        Map<String, Object> payload = new HashMap<>();
        payload.put("caption", caption);
        payload.put("postType", "image");
        payload.put("mediaIds", List.of(mediaId.toString()));
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts",
                        HttpMethod.POST,
                        new HttpEntity<>(payload, authHeaders(author)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String uniqueIp() {
        java.util.Random rand = new java.util.Random();
        return "10." + rand.nextInt(256) + "." + rand.nextInt(256) + "." + (1 + rand.nextInt(254));
    }
}
