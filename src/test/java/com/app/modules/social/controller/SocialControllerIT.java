package com.app.modules.social.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

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
class SocialControllerIT {

    private static final String JWT_SECRET = "social-controller-it-secret-32-chars-min!!";
    private static final String JWT_ISSUER = "https://social.it.local";
    private static final String JWT_AUDIENCE = "App";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        // These tests drive login, register and report submission as setup, not as the
        // subject under test. The kill switch keeps them off the network: the dev profile
        // defaults the secret to Cloudflare's test key, and a real siteverify call would
        // make the suite depend on an external service being reachable.
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> JWT_SECRET);
        r.add("JWT_ISSUER", () -> JWT_ISSUER);
        r.add("JWT_AUDIENCE", () -> JWT_AUDIENCE);
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "Social IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private JdbcTemplate jdbc;

    // ── POST /api/v1/social/follow/{targetUserId} ─────────────────────────────

    @Test
    void follow_publicTarget_returns201() {
        String actorToken = registerAndLogin("sc_follow_a");
        String targetEmail = unique("sc_follow_b");
        registerAndLogin("sc_follow_b_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);

        ResponseEntity<Map> response =
                exchange("/api/v1/social/follow/" + targetId, HttpMethod.POST, null, actorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = data(response);
        assertThat(data.get("followingId").toString()).isEqualTo(targetId.toString());
    }

    @Test
    void follow_privateTarget_returns201WithPendingStatus() {
        String actorToken = registerAndLogin("sc_priv_actor");
        String targetEmail = unique("sc_priv_target");
        registerAndLogin("sc_priv_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);
        jdbc.update("UPDATE users SET is_private = true WHERE id = ?", targetId);

        ResponseEntity<Map> response =
                exchange("/api/v1/social/follow/" + targetId, HttpMethod.POST, null, actorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = data(response);
        assertThat(data.get("status").toString()).isEqualToIgnoringCase("pending");
    }

    @Test
    void follow_withoutToken_returns401() {
        UUID randomId = UUID.randomUUID();

        ResponseEntity<Map> response =
                exchange("/api/v1/social/follow/" + randomId, HttpMethod.POST, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── DELETE /api/v1/social/follow/{targetUserId} ───────────────────────────

    @Test
    void unfollow_existingFollow_returns204() {
        String actorToken = registerAndLogin("sc_unf_actor");
        String targetEmail = unique("sc_unf_target");
        registerAndLogin("sc_unf_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);
        exchange("/api/v1/social/follow/" + targetId, HttpMethod.POST, null, actorToken);

        ResponseEntity<Map> response =
                exchange("/api/v1/social/follow/" + targetId, HttpMethod.DELETE, null, actorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── GET /api/v1/social/users/{userId}/followers ───────────────────────────

    @Test
    void getFollowers_afterFollow_returns200() {
        String actorToken = registerAndLogin("sc_gf_actor");
        String targetEmail = unique("sc_gf_target");
        String targetToken = registerAndLogin("sc_gf_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);
        exchange("/api/v1/social/follow/" + targetId, HttpMethod.POST, null, actorToken);

        ResponseEntity<Map> response =
                exchange(
                        "/api/v1/social/users/" + targetId + "/followers",
                        HttpMethod.GET,
                        null,
                        targetToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getFollowers_limitAboveMax_returnsBadRequest() {
        String targetEmail = unique("sc_gfl_target");
        String targetToken = registerAndLogin("sc_gfl_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);

        ResponseEntity<Map> response =
                exchange(
                        "/api/v1/social/users/" + targetId + "/followers?limit=999",
                        HttpMethod.GET,
                        null,
                        targetToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── GET /api/v1/social/users/{userId}/following ───────────────────────────

    @Test
    void getFollowing_afterFollow_returns200() {
        String actorEmail = unique("sc_gfw_actor");
        String actorToken = registerAndLogin("sc_gfw_actor_u", actorEmail);
        UUID actorId = userIdByEmail(actorEmail);
        String targetEmail = unique("sc_gfw_target");
        registerAndLogin("sc_gfw_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);
        exchange("/api/v1/social/follow/" + targetId, HttpMethod.POST, null, actorToken);

        ResponseEntity<Map> response =
                exchange(
                        "/api/v1/social/users/" + actorId + "/following",
                        HttpMethod.GET,
                        null,
                        actorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── POST /api/v1/social/block/{targetUserId} ──────────────────────────────

    @Test
    void block_existingUser_returns201() {
        String actorToken = registerAndLogin("sc_blk_actor");
        String targetEmail = unique("sc_blk_target");
        registerAndLogin("sc_blk_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);

        ResponseEntity<Map> response =
                exchange("/api/v1/social/block/" + targetId, HttpMethod.POST, null, actorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── DELETE /api/v1/social/block/{targetUserId} ────────────────────────────

    @Test
    void unblock_existingBlock_returns204() {
        String actorToken = registerAndLogin("sc_ublk_actor");
        String targetEmail = unique("sc_ublk_target");
        registerAndLogin("sc_ublk_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);
        exchange("/api/v1/social/block/" + targetId, HttpMethod.POST, null, actorToken);

        ResponseEntity<Map> response =
                exchange("/api/v1/social/block/" + targetId, HttpMethod.DELETE, null, actorToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── GET /api/v1/social/follow-requests ───────────────────────────────────

    @Test
    void getPendingFollowRequests_returns200() {
        String targetToken = registerAndLogin("sc_pfr_target");

        ResponseEntity<Map> response =
                exchange("/api/v1/social/follow-requests", HttpMethod.GET, null, targetToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("data")).isNotNull();
    }

    // ── PATCH /api/v1/social/follow-requests/{requesterId}/approve ────────────

    @Test
    void approveFollowRequest_pendingRequest_returns204() {
        String actorEmail = unique("sc_apr_actor");
        String actorToken = registerAndLogin("sc_apr_actor_u", actorEmail);
        UUID actorId = userIdByEmail(actorEmail);
        String targetEmail = unique("sc_apr_target");
        String targetToken = registerAndLogin("sc_apr_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);
        jdbc.update("UPDATE users SET is_private = true WHERE id = ?", targetId);
        exchange("/api/v1/social/follow/" + targetId, HttpMethod.POST, null, actorToken);

        ResponseEntity<Map> response =
                exchange(
                        "/api/v1/social/follow-requests/" + actorId + "/approve",
                        HttpMethod.PATCH,
                        null,
                        targetToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── PATCH /api/v1/social/follow-requests/{requesterId}/reject ─────────────

    @Test
    void rejectFollowRequest_pendingRequest_returns204() {
        String actorEmail = unique("sc_rjt_actor");
        String actorToken = registerAndLogin("sc_rjt_actor_u", actorEmail);
        UUID actorId = userIdByEmail(actorEmail);
        String targetEmail = unique("sc_rjt_target");
        String targetToken = registerAndLogin("sc_rjt_target_u", targetEmail);
        UUID targetId = userIdByEmail(targetEmail);
        jdbc.update("UPDATE users SET is_private = true WHERE id = ?", targetId);
        exchange("/api/v1/social/follow/" + targetId, HttpMethod.POST, null, actorToken);

        ResponseEntity<Map> response =
                exchange(
                        "/api/v1/social/follow-requests/" + actorId + "/reject",
                        HttpMethod.PATCH,
                        null,
                        targetToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String registerAndLogin(String username) {
        return registerAndLogin(username, unique(username));
    }

    @SuppressWarnings("unchecked")
    private String registerAndLogin(String username, String email) {
        String password = "Password1!";
        postJson(
                "/api/v1/auth/register",
                Map.of("username", username, "email", email, "password", password));
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String verToken = tokenService.createEmailVerificationToken(userId);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + verToken, Map.class);
        ResponseEntity<Map> login =
                postJson("/api/v1/auth/login", Map.of("identifier", email, "password", password));
        Map<String, String> data = (Map<String, String>) login.getBody().get("data");
        return data.get("accessToken");
    }

    private UUID userIdByEmail(String email) {
        return userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
    }

    private ResponseEntity<Map> postJson(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> exchange(
            String path, HttpMethod method, Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private static String unique(String tag) {
        return tag + "_" + UUID.randomUUID().toString().substring(0, 6) + "@sc.test";
    }
}
