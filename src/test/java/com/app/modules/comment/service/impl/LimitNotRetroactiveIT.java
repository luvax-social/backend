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

/**
 * An interaction limit decides only the comments written while it is in force: turning one on never
 * hides a comment a non-follower already wrote, and turning it off never brings a hidden one back.
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
class LimitNotRetroactiveIT {

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
        r.add("JWT_SECRET", () -> "limit-retro-it-secret-that-is-32-chars-minimum!");
        r.add("JWT_ISSUER", () -> "https://limit.retro.it.local");
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

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM comment_write_idempotency");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_media");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM interaction_limits");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM oauth_accounts");
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void turningALimitOn_afterANonFollowerCommented_leavesTheOldCommentApprovedAndListed() {
        TestUser owner = registerUser("lnr_on_owner");
        TestUser actor = registerUser("lnr_on_actor");
        UUID postId = createImagePost(owner, "limit on post");
        UUID earlier = createComment(actor, postId, "written before the limit");

        setLimit(owner, true, false);
        UUID later = createComment(actor, postId, "written under the limit");

        assertThat(statusOf(earlier)).isEqualTo("approved");
        assertThat(statusOf(later)).isEqualTo("hidden");
        assertThat(commentCount(postId)).isEqualTo(1);
        assertThat(listedIds(owner, postId)).contains(earlier.toString());
    }

    @Test
    void turningALimitOff_afterANonFollowerCommentWasHidden_leavesItHidden() {
        TestUser owner = registerUser("lnr_off_owner");
        TestUser actor = registerUser("lnr_off_actor");
        UUID postId = createImagePost(owner, "limit off post");
        setLimit(owner, true, false);
        UUID commentId = createComment(actor, postId, "written under the limit");

        ResponseEntity<Void> off =
                rest.exchange(
                        "/api/v1/users/me/interaction-limits",
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(owner)),
                        Void.class);
        assertThat(off.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(statusOf(commentId)).isEqualTo("hidden");
        assertThat(hiddenReasonOf(commentId)).isEqualTo("limited_interaction");
        assertThat(commentCount(postId)).isZero();
    }

    private void setLimit(TestUser owner, boolean nonFollowers, boolean recentFollowers) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/users/me/interaction-limits",
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                Map.of(
                                        "limitNonFollowers",
                                        nonFollowers,
                                        "limitRecentFollowers",
                                        recentFollowers,
                                        "durationDays",
                                        7),
                                authHeaders(owner)),
                        Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private UUID createComment(TestUser user, UUID postId, String content) {
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
    private List<String> listedIds(TestUser viewer, UUID postId) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/posts/" + postId + "/comments",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(viewer)),
                        Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return ((List<Map<?, ?>>) data.get("content"))
                .stream().map(row -> (String) row.get("id")).toList();
    }

    private String statusOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM comments WHERE id = ?", String.class, commentId);
    }

    private String hiddenReasonOf(UUID commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT hidden_reason FROM comments WHERE id = ?", String.class, commentId);
    }

    private int commentCount(UUID postId) {
        return jdbcTemplate.queryForObject(
                "SELECT comment_count FROM posts WHERE id = ?", Integer.class, postId);
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
