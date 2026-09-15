package com.app.modules.users.controller;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.auth.service.TokenService;
import com.app.modules.users.entity.User;
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
class UserControllerIT {

    private static final String TEST_PASSWORD = "S3cur3P@ssword";

    private static final String TEST_JWT_SECRET = "integration-test-secret-32-chars-minimum-len!!";
    private static final String TEST_JWT_ISSUER = "https://it.test.local";
    private static final String TEST_JWT_AUDIENCE = "App";

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
        r.add("JWT_SECRET", () -> TEST_JWT_SECRET);
        r.add("JWT_ISSUER", () -> TEST_JWT_ISSUER);
        r.add("JWT_AUDIENCE", () -> TEST_JWT_AUDIENCE);
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
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;

    // ── GET /api/v1/users/me ──────────────────────────────────────────────────

    @Test
    void getMyProfile_withValidToken_returns200() {
        String email = uniqueEmail("me_ok");
        String access =
                registerVerifyAndLogin("user_me_ok", email, TEST_PASSWORD).get("accessToken");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/me", access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("email")).isEqualTo(email);
    }

    @Test
    void getMyProfile_withoutToken_returns401() {
        ResponseEntity<Map> response = getNoAuth("/api/v1/users/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PATCH /api/v1/users/me ────────────────────────────────────────────────

    @Test
    void updateMyProfile_withValidToken_returns200() {
        String email = uniqueEmail("patch_ok");
        String access =
                registerVerifyAndLogin("user_patch_ok", email, TEST_PASSWORD).get("accessToken");

        ResponseEntity<Map> response =
                patchWithAuth("/api/v1/users/me", Map.of("displayName", "Updated Name"), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("displayName")).isEqualTo("Updated Name");
    }

    @Test
    void updateMyProfile_takenUsername_returns409() {
        String email1 = uniqueEmail("cnfl1");
        String email2 = uniqueEmail("cnfl2");
        registerVerifyAndLogin("user_cnfl1", email1, TEST_PASSWORD);
        String access2 =
                registerVerifyAndLogin("user_cnfl2", email2, TEST_PASSWORD).get("accessToken");

        ResponseEntity<Map> response =
                patchWithAuth("/api/v1/users/me", Map.of("username", "user_cnfl1"), access2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("USER_USERNAME_ALREADY_EXISTS");
    }

    @Test
    void updateMyProfile_usernameHeldBySoftDeletedAccount_returnsConsistentConflict() {
        String softDeletedEmail = uniqueEmail("softdel");
        registerVerifyAndLogin("user_softdel", softDeletedEmail, TEST_PASSWORD);
        User softDeleted =
                userRepository.findByEmailAndDeletedAtIsNull(softDeletedEmail).orElseThrow();
        softDeleted.setDeletedAt(java.time.OffsetDateTime.now());
        userRepository.save(softDeleted);

        String access =
                registerVerifyAndLogin("user_live", uniqueEmail("live"), TEST_PASSWORD)
                        .get("accessToken");

        ResponseEntity<Map> response =
                patchWithAuth("/api/v1/users/me", Map.of("username", "user_softdel"), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("USER_USERNAME_ALREADY_EXISTS");
    }

    @Test
    void updateMyProfile_invalidUsername_returns400() {
        // Finding F-1: spec says 422 but VALIDATION_ERROR maps to HttpStatus.BAD_REQUEST (400)
        String email = uniqueEmail("val_fail");
        String access =
                registerVerifyAndLogin("user_val_fail", email, TEST_PASSWORD).get("accessToken");
        String tooLong = "a".repeat(31);

        ResponseEntity<Map> response =
                patchWithAuth("/api/v1/users/me", Map.of("username", tooLong), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void updateMyProfile_withoutToken_returns401() {
        ResponseEntity<Map> response =
                patchNoAuth("/api/v1/users/me", Map.of("displayName", "Hacker"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void updateMyProfile_bannerUrl_setsAndReturnsOnSelfAndPublicProfile() {
        String email = uniqueEmail("banner_set");
        String access =
                registerVerifyAndLogin("user_banner_set", email, TEST_PASSWORD).get("accessToken");
        String bannerUrl = "https://cdn.example.com/banners/banner_set.jpg";

        ResponseEntity<Map> patchResponse =
                patchWithAuth("/api/v1/users/me", Map.of("bannerUrl", bannerUrl), access);

        assertThat(patchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> patchData = (Map<?, ?>) patchResponse.getBody().get("data");
        assertThat(patchData.get("bannerUrl")).isEqualTo(bannerUrl);

        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        ResponseEntity<Map> publicResponse = getNoAuth("/api/v1/users/" + userId);
        Map<?, ?> publicData = (Map<?, ?>) publicResponse.getBody().get("data");
        assertThat(publicData.get("bannerUrl")).isEqualTo(bannerUrl);
    }

    @Test
    void updateMyProfile_emptyStringBannerUrl_clearsBanner() {
        String email = uniqueEmail("banner_clear");
        String access =
                registerVerifyAndLogin("user_banner_clear", email, TEST_PASSWORD)
                        .get("accessToken");
        patchWithAuth(
                "/api/v1/users/me",
                Map.of("bannerUrl", "https://cdn.example.com/banners/banner_clear.jpg"),
                access);

        ResponseEntity<Map> response =
                patchWithAuth("/api/v1/users/me", Map.of("bannerUrl", ""), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("bannerUrl")).isNull();
    }

    // ── GET /api/v1/users/{userId} ────────────────────────────────────────────

    @Test
    void getUserProfile_publicAccount_unauthenticated_returns200WithNullCounts() {
        String email = uniqueEmail("pub_unauth");
        registerVerifyAndLogin("user_pub_unauth", email, TEST_PASSWORD);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();

        ResponseEntity<Map> response = getNoAuth("/api/v1/users/" + userId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("followerCount")).isNull();
        assertThat(data.get("followingCount")).isNull();
        assertThat(data.get("postCount")).isNull();
    }

    @Test
    void getUserProfile_publicAccount_authenticated_returns200WithCounts() {
        String targetEmail = uniqueEmail("pub_target");
        registerVerifyAndLogin("user_pub_target", targetEmail, TEST_PASSWORD);
        UUID targetId =
                userRepository.findByEmailAndDeletedAtIsNull(targetEmail).orElseThrow().getId();

        String callerEmail = uniqueEmail("pub_caller");
        String callerAccess =
                registerVerifyAndLogin("user_pub_caller", callerEmail, TEST_PASSWORD)
                        .get("accessToken");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/" + targetId, callerAccess);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("followerCount")).isNotNull();
    }

    @Test
    void getUserProfile_privateAccount_unauthenticated_returns200WithMaskedCounts() {
        String email = uniqueEmail("priv_target");
        String access =
                registerVerifyAndLogin("user_priv_target", email, TEST_PASSWORD).get("accessToken");
        UUID targetId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();

        patchWithAuth("/api/v1/users/me", Map.of("isPrivate", true), access);

        ResponseEntity<Map> response = getNoAuth("/api/v1/users/" + targetId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("followerCount")).isNull();
        assertThat(data.get("followingCount")).isNull();
        assertThat(data.get("postCount")).isNull();
    }

    @Test
    void getUserProfile_privateAccount_authenticatedNonFollower_returns200WithMaskedCounts() {
        String targetEmail = uniqueEmail("priv_target2");
        String targetAccess =
                registerVerifyAndLogin("user_priv_target2", targetEmail, TEST_PASSWORD)
                        .get("accessToken");
        UUID targetId =
                userRepository.findByEmailAndDeletedAtIsNull(targetEmail).orElseThrow().getId();
        patchWithAuth("/api/v1/users/me", Map.of("isPrivate", true), targetAccess);

        String callerEmail = uniqueEmail("priv_caller");
        String callerAccess =
                registerVerifyAndLogin("user_priv_caller", callerEmail, TEST_PASSWORD)
                        .get("accessToken");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/" + targetId, callerAccess);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("followerCount")).isNull();
        assertThat(data.get("followingCount")).isNull();
        assertThat(data.get("postCount")).isNull();
    }

    @Test
    void getUserProfile_nonExistentUser_returns404() {
        ResponseEntity<Map> response = getNoAuth("/api/v1/users/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── GET /api/v1/users/me/settings ────────────────────────────────────────

    @Test
    void getMySettings_withValidToken_returns200() {
        String email = uniqueEmail("sett_ok");
        String access =
                registerVerifyAndLogin("user_sett_ok", email, TEST_PASSWORD).get("accessToken");

        ResponseEntity<Map> response = getWithAuth("/api/v1/users/me/settings", access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("notifyLikes")).isEqualTo(true);
    }

    @Test
    void getMySettings_withoutToken_returns401() {
        ResponseEntity<Map> response = getNoAuth("/api/v1/users/me/settings");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── PATCH /api/v1/users/me/settings ──────────────────────────────────────

    @Test
    void updateMySettings_partialUpdate_nullFieldsPreserved() {
        String email = uniqueEmail("sett_patch");
        String access =
                registerVerifyAndLogin("user_sett_patch", email, TEST_PASSWORD).get("accessToken");

        ResponseEntity<Map> response =
                patchWithAuth("/api/v1/users/me/settings", Map.of("notifyLikes", false), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("notifyLikes")).isEqualTo(false);
        assertThat(data.get("notifyComments")).isEqualTo(true);
        assertThat(data.get("notifyFollows")).isEqualTo(true);
    }

    @Test
    void updateMySettings_withoutToken_returns401() {
        ResponseEntity<Map> response =
                patchNoAuth("/api/v1/users/me/settings", Map.of("notifyLikes", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> registerVerifyAndLogin(
            String username, String email, String password) {
        postJson(
                "/api/v1/auth/register",
                Map.of("username", username, "email", email, "password", password));
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String verToken = tokenService.createEmailVerificationToken(userId);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + verToken, Map.class);
        ResponseEntity<Map> login =
                postJson("/api/v1/auth/login", Map.of("identifier", email, "password", password));
        return (Map<String, String>) login.getBody().get("data");
    }

    private ResponseEntity<Map> postJson(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> getWithAuth(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> getNoAuth(String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
    }

    private ResponseEntity<Map> patchWithAuth(String path, Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> patchNoAuth(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers), Map.class);
    }

    private static String uniqueEmail(String tag) {
        return tag + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
