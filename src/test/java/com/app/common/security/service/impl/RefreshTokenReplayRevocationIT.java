package com.app.common.security.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

/**
 * Proves that replaying a consumed refresh token durably revokes the whole token family.
 *
 * <p>This asserts committed state and end-to-end behaviour, not a repository interaction. The
 * revocation runs inside {@code rotate}'s own {@code REQUIRES_NEW} transaction and is followed
 * immediately by an {@code AppException}, so whether it survives is decided entirely by that
 * transaction's rollback rules. A mocked unit test satisfies {@code verify(repository)} the instant
 * the call executes and therefore stays green while the rollback discards the write in production,
 * which is exactly how the defect reached a pre-production candidate with three assertions
 * apparently covering it. There is deliberately no {@code @Transactional} on this class: the
 * assertions query the database directly and must see what actually committed.
 */
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
class RefreshTokenReplayRevocationIT {

    private static final String TEST_PASSWORD = "S3cur3P@ssword";
    private static final String REFRESH_COOKIE = "luvax_refresh";

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
        r.add("JWT_SECRET", () -> "refresh-replay-it-secret-32-chars-minimum!!!");
        r.add("JWT_ISSUER", () -> "https://refresh-replay.it.local");
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
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void rotate_consumedTokenReplayed_revokesEverySessionAndRejectsTheSuccessor() {
        String email = uniqueEmail("replay");
        String firstToken = registerVerifyAndLogin(uniqueUsername("replay"), email);
        UUID userId = userId(email);

        ResponseEntity<Map> rotation = refresh(firstToken);
        assertThat(rotation.getStatusCode()).isEqualTo(HttpStatus.OK);
        String successor = refreshCookieValue(rotation);
        // Verifying the address and then logging in each mint a session, so more than one is live
        // here. That is what makes this a family revocation rather than a single-token one.
        assertThat(activeSessions(userId)).isGreaterThan(1);

        ResponseEntity<Map> replay = refresh(firstToken);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The committed effect, which is the whole point of this class. Before the revocation was
        // made to survive its own rejection this stayed at 1, because the AppException thrown one
        // line later rolled the revoking UPDATE back.
        assertThat(activeSessions(userId)).isZero();
        // And the same fact through the front door: the thief's successor must be dead too.
        assertThat(refresh(successor).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rotate_singleLegitimateRotation_issuesAWorkingSuccessor() {
        String email = uniqueEmail("happy");
        String firstToken = registerVerifyAndLogin(uniqueUsername("happy"), email);
        UUID userId = userId(email);
        long before = activeSessions(userId);

        ResponseEntity<Map> rotation = refresh(firstToken);

        assertThat(rotation.getStatusCode()).isEqualTo(HttpStatus.OK);
        // One token in, one token out: an ordinary rotation revokes nothing else.
        assertThat(activeSessions(userId)).isEqualTo(before);
        // Guards against closing the defect by revoking unconditionally: the successor of an
        // ordinary rotation has to keep working.
        assertThat(refresh(refreshCookieValue(rotation)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rotate_consumedTokenReplayed_leavesOtherAccountsSessionsUntouched() {
        String victimEmail = uniqueEmail("victim");
        String victimToken = registerVerifyAndLogin(uniqueUsername("victim"), victimEmail);
        String bystanderEmail = uniqueEmail("bystander");
        registerVerifyAndLogin(uniqueUsername("bystander"), bystanderEmail);
        long bystanderSessions = activeSessions(userId(bystanderEmail));
        refresh(victimToken);

        assertThat(refresh(victimToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(activeSessions(userId(victimEmail))).isZero();
        // The revocation is scoped to the account that tripped it, not to the whole table.
        assertThat(activeSessions(userId(bystanderEmail))).isEqualTo(bystanderSessions);
    }

    private ResponseEntity<Map> refresh(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, REFRESH_COOKIE + "=" + refreshToken);
        return rest.exchange(
                "/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    private String registerVerifyAndLogin(String username, String email) {
        postJson(
                "/api/v1/auth/register",
                Map.of("username", username, "email", email, "password", TEST_PASSWORD));
        rest.getForEntity(
                "/api/v1/auth/verify-email?token=" + createVerificationToken(email), Map.class);
        ResponseEntity<Map> login =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));
        return refreshCookieValue(login);
    }

    private ResponseEntity<Map> postJson(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private String createVerificationToken(String email) {
        return tokenService.createEmailVerificationToken(userId(email));
    }

    private UUID userId(String email) {
        return userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
    }

    private long activeSessions(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Long.class,
                userId);
    }

    private static String refreshCookieValue(ResponseEntity<?> response) {
        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).as("Set-Cookie headers").isNotNull();
        String header =
                setCookies.stream()
                        .filter(h -> h.startsWith(REFRESH_COOKIE + "="))
                        .findFirst()
                        .orElseThrow();
        String nameValuePair = header.split(";", 2)[0];
        return nameValuePair.substring(nameValuePair.indexOf('=') + 1);
    }

    private static String uniqueEmail(String tag) {
        return tag + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static String uniqueUsername(String tag) {
        return tag + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
