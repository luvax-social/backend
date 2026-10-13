package com.app.modules.post.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.auth.service.TokenService;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class PostNotInterestedControllerIT {

    private static final String JWT_SECRET = "post-not-interested-it-secret-32-chars-min!!";

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
        r.add("JWT_SECRET", () -> JWT_SECRET);
        r.add("JWT_ISSUER", () -> "https://post-not-interested.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "Post Not Interested IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        // The publisher stays off so every enqueued event remains a PENDING row to inspect.
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void markNotInterested_visiblePost_returns204StoresOneRowAndEnqueuesEvent() {
        Account viewer = account("pni_viewer");
        Account author = account("pni_author");
        UUID postId = insertPublishedPost(author.id());

        ResponseEntity<String> response = send(HttpMethod.POST, path(postId), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(viewer.id(), postId)).isEqualTo(1);
        List<Map<String, Object>> events = events("post.not-interested.v1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0))
                .containsEntry("postId", postId.toString())
                .containsEntry("authorId", author.id().toString())
                .hasSize(2);
    }

    @Test
    void markNotInterested_repeatCall_isNoOpWithoutSecondEvent() {
        Account viewer = account("pni_repeat_v");
        Account author = account("pni_repeat_a");
        UUID postId = insertPublishedPost(author.id());

        send(HttpMethod.POST, path(postId), viewer.token());
        ResponseEntity<String> second = send(HttpMethod.POST, path(postId), viewer.token());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(viewer.id(), postId)).isEqualTo(1);
        assertThat(events("post.not-interested.v1")).hasSize(1);
    }

    @Test
    void markNotInterested_ownPost_returns400PostFeedbackSelf() {
        Account author = account("pni_self");
        UUID postId = insertPublishedPost(author.id());

        ResponseEntity<String> response = send(HttpMethod.POST, path(postId), author.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(response)).isEqualTo("POST_FEEDBACK_SELF");
        assertThat(rowCount(author.id(), postId)).isZero();
        assertThat(events("post.not-interested.v1")).isEmpty();
    }

    @Test
    void markNotInterested_missingPost_returns404PostNotFound() {
        Account viewer = account("pni_missing");

        ResponseEntity<String> response =
                send(HttpMethod.POST, path(UUID.randomUUID()), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(response)).isEqualTo("POST_NOT_FOUND");
    }

    @Test
    void markNotInterested_postOfBlockingAuthor_returns404PostNotFound() {
        Account viewer = account("pni_blocked_v");
        Account author = account("pni_blocked_a");
        UUID postId = insertPublishedPost(author.id());
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                author.id(),
                viewer.id());

        ResponseEntity<String> response = send(HttpMethod.POST, path(postId), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errorCode(response)).isEqualTo("POST_NOT_FOUND");
        assertThat(rowCount(viewer.id(), postId)).isZero();
    }

    @Test
    void markNotInterested_privateAuthorNotFollowed_returns404PostNotFound() {
        Account viewer = account("pni_priv_v");
        Account author = account("pni_priv_a");
        jdbc.update("UPDATE users SET is_private = true WHERE id = ?", author.id());
        UUID postId = insertPublishedPost(author.id());

        ResponseEntity<String> response = send(HttpMethod.POST, path(postId), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void markNotInterested_withoutToken_returns401() {
        ResponseEntity<String> response = send(HttpMethod.POST, path(UUID.randomUUID()), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void undoNotInterested_rowExisted_returns204DeletesAndEnqueuesUndoneEvent() {
        Account viewer = account("pni_undo_v");
        Account author = account("pni_undo_a");
        UUID postId = insertPublishedPost(author.id());
        send(HttpMethod.POST, path(postId), viewer.token());
        jdbc.update("DELETE FROM outbox_events");

        ResponseEntity<String> response = send(HttpMethod.DELETE, path(postId), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(viewer.id(), postId)).isZero();
        List<Map<String, Object>> events = events("post.not-interested-undone.v1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("postId", postId.toString()).hasSize(1);
    }

    @Test
    void undoNotInterested_noRow_returns204WithoutEvent() {
        Account viewer = account("pni_undo_none");
        UUID postId = insertPublishedPost(account("pni_undo_none_a").id());

        ResponseEntity<String> response = send(HttpMethod.DELETE, path(postId), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(events("post.not-interested-undone.v1")).isEmpty();
    }

    @Test
    void undoNotInterested_afterAuthorBecameInvisible_stillRemovesTheRow() {
        Account viewer = account("pni_undo_inv_v");
        Account author = account("pni_undo_inv_a");
        UUID postId = insertPublishedPost(author.id());
        send(HttpMethod.POST, path(postId), viewer.token());
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                author.id(),
                viewer.id());

        ResponseEntity<String> response = send(HttpMethod.DELETE, path(postId), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(viewer.id(), postId)).isZero();
    }

    private static String path(UUID postId) {
        return "/api/v1/posts/" + postId + "/not-interested";
    }

    private int rowCount(UUID userId, UUID postId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM post_not_interested WHERE user_id = ? AND post_id = ?",
                Integer.class,
                userId,
                postId);
    }

    // The payload column is the envelope; the event's own fields sit under "data". Comparing the
    // whole parsed map also proves no null-valued key was written.
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(String eventType) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM outbox_events WHERE event_type = ? ORDER BY"
                                + " created_at",
                        String.class,
                        eventType)
                .stream()
                .map(json -> (Map<String, Object>) JSON.readValue(json, Map.class).get("data"))
                .toList();
    }

    private String errorCode(ResponseEntity<String> response) {
        return String.valueOf(JSON.readValue(response.getBody(), Map.class).get("code"));
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(null, headers), String.class);
    }

    private UUID insertPublishedPost(UUID userId) {
        return jdbc.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }

    private record Account(UUID id, String token) {}

    @SuppressWarnings("unchecked")
    private Account account(String username) {
        String email = username + "_" + UUID.randomUUID().toString().substring(0, 6) + "@pni.test";
        String password = "Password1!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(
                "/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("username", username, "email", email, "password", password),
                        headers),
                Map.class);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        rest.getForEntity(
                "/api/v1/auth/verify-email?token="
                        + tokenService.createEmailVerificationToken(userId),
                Map.class);
        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        Map<String, String> data = (Map<String, String>) login.getBody().get("data");
        return new Account(userId, data.get("accessToken"));
    }
}
