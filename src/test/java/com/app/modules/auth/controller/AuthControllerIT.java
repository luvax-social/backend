package com.app.modules.auth.controller;

import static com.app.modules.auth.messaging.AuthEventTypes.AUTH_EMAIL_VERIFICATION_REQUESTED_V1;
import static com.app.modules.auth.messaging.AuthEventTypes.USER_REGISTERED_V1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.http.server.LocalTestWebServer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.enums.ApiSuccessCode;
import com.app.common.response.ApiResponse;
import com.app.common.security.util.SecurityUtils;
import com.app.modules.auth.entity.RefreshToken;
import com.app.modules.auth.repository.RefreshTokenRepository;
import com.app.modules.auth.service.OAuth2ExchangeCodeService;
import com.app.modules.auth.service.TokenService;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

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
@Import(AuthControllerIT.IntegrationTestConfig.class)
class AuthControllerIT {

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
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private TokenService tokenService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OAuth2ExchangeCodeService oauth2ExchangeCodeService;

    // The whole suspension lifecycle through the front door: refused while the term stands,
    // admitted
    // on the very first attempt after it lapses, and the row repaired by that same attempt rather
    // than by a later sweep.
    @Test
    void login_fixedTermSuspension_isRefusedBeforeExpiryAndAdmittedAfterWithTheRowRepaired() {
        String email = uniqueEmail("suspend_expiry");
        registerVerifyAndLogin(uniqueUsername("suspexp"), email, TEST_PASSWORD);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        jdbcTemplate.update(
                "UPDATE users SET status = 'suspended', suspended_until = now() + INTERVAL '1 day'"
                        + " WHERE id = ?",
                userId);

        ResponseEntity<Map> refused =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo("AUTH_ACCOUNT_INACTIVE");
        assertThat(statusOf(userId)).isEqualTo("suspended");

        jdbcTemplate.update(
                "UPDATE users SET suspended_until = now() - INTERVAL '1 hour' WHERE id = ?",
                userId);

        ResponseEntity<Map> admitted =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));

        assertThat(admitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(userId)).isEqualTo("active");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT suspended_until FROM users WHERE id = ?",
                                OffsetDateTime.class,
                                userId))
                .isNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE target_user_id = ?"
                                        + " AND action_type = 'unsuspend_user' AND admin_id IS NULL",
                                Integer.class,
                                userId))
                .isEqualTo(1);
    }

    // An indefinite suspension has no deadline, so nothing reinstates it and the refusal stands
    // however many times the account tries.
    @Test
    void login_indefiniteSuspension_isRefusedAndNeverRepaired() {
        String email = uniqueEmail("suspend_indef");
        registerVerifyAndLogin(uniqueUsername("suspind"), email, TEST_PASSWORD);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        jdbcTemplate.update(
                "UPDATE users SET status = 'suspended', suspended_until = NULL WHERE id = ?",
                userId);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(
                            postJson(
                                            "/api/v1/auth/login",
                                            Map.of("identifier", email, "password", TEST_PASSWORD))
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        assertThat(statusOf(userId)).isEqualTo("suspended");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE target_user_id = ?",
                                Integer.class,
                                userId))
                .isZero();
    }

    private String statusOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void register_recordsTheOriginTheAccountWasCreatedFrom() {
        String email = uniqueEmail("reg_ip");
        String username = uniqueUsername("regip");

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/register",
                        registerBody(username, email, TEST_PASSWORD),
                        "198.51.100.42");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT host(registration_ip) FROM users WHERE id = ?",
                                String.class,
                                userId))
                .isEqualTo("198.51.100.42");
        // Nothing has logged in yet, so the login columns must still be empty. A registration that
        // also set them would make last_login_at mean "account exists" rather than "account used".
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT last_login_at FROM users WHERE id = ?",
                                OffsetDateTime.class,
                                userId))
                .isNull();
    }

    @Test
    void login_advancesLastLoginAtAndRecordsTheOrigin() {
        String email = uniqueEmail("login_ip");
        String username = uniqueUsername("loginip");
        postJson("/api/v1/auth/register", registerBody(username, email, TEST_PASSWORD));
        String verificationToken = createVerificationToken(email);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + verificationToken, Map.class);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();

        ResponseEntity<Map> login =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD),
                        "203.0.113.77");

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT last_login_at FROM users WHERE id = ?",
                                OffsetDateTime.class,
                                userId))
                .isNotNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT host(last_login_ip) FROM users WHERE id = ?",
                                String.class,
                                userId))
                .isEqualTo("203.0.113.77");
    }

    // A last-login timestamp that moved on every token refresh would report an idle background tab
    // as recent activity and stop being a login signal at all.
    @Test
    void refresh_doesNotAdvanceLastLoginAt() {
        String email = uniqueEmail("refresh_lastlogin");
        Map<?, ?> sessionData =
                registerVerifyAndLogin(uniqueUsername("rlogin"), email, TEST_PASSWORD);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        OffsetDateTime afterLogin =
                jdbcTemplate.queryForObject(
                        "SELECT last_login_at FROM users WHERE id = ?",
                        OffsetDateTime.class,
                        userId);
        assertThat(afterLogin).isNotNull();
        // Rewind the stored value so any write by the refresh path is unmistakable rather than a
        // sub-millisecond difference that could be argued either way.
        jdbcTemplate.update(
                "UPDATE users SET last_login_at = ? WHERE id = ?", afterLogin.minusDays(3), userId);

        ResponseEntity<Map> refreshed =
                postJson(
                        "/api/v1/auth/refresh",
                        Map.of("refreshToken", sessionData.get("refreshToken")));

        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT last_login_at FROM users WHERE id = ?",
                                OffsetDateTime.class,
                                userId))
                .isCloseTo(
                        afterLogin.minusDays(3), within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    void refresh_bannedUser_returns403AndOldTokenIsDurablyRevoked() {
        String email = uniqueEmail("refresh_banned");
        Map<?, ?> sessionData = registerVerifyAndLogin("user_bnnd", email, TEST_PASSWORD);
        String oldRefreshToken = (String) sessionData.get("refreshToken");

        userRepository
                .findByEmailAndDeletedAtIsNull(email)
                .ifPresent(
                        u -> {
                            u.setStatus(UserStatus.BANNED);
                            userRepository.save(u);
                        });

        ResponseEntity<Map> response =
                postJson("/api/v1/auth/refresh", Map.of("refreshToken", oldRefreshToken));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_ACCOUNT_LOCKED");

        // Critical assertion: the old token row must have revoked_at committed even though
        // the outer refresh transaction threw. Proves REQUIRES_NEW on rotate() is in effect.
        RefreshToken oldRow =
                refreshTokenRepository
                        .findByTokenHash(sha256(oldRefreshToken))
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "old refresh_tokens row was deleted — expected"
                                                        + " revoked_at to be set instead"));
        assertThat(oldRow.getRevokedAt())
                .as("old refresh token must have revoked_at set (durable revocation)")
                .isNotNull();
    }

    @Test
    void refresh_suspendedUser_returns403AndOldTokenIsDurablyRevoked() {
        String email = uniqueEmail("refresh_susp");
        Map<?, ?> sessionData = registerVerifyAndLogin("user_susp", email, TEST_PASSWORD);
        String oldRefreshToken = (String) sessionData.get("refreshToken");

        userRepository
                .findByEmailAndDeletedAtIsNull(email)
                .ifPresent(
                        u -> {
                            u.setStatus(UserStatus.SUSPENDED);
                            userRepository.save(u);
                        });

        ResponseEntity<Map> response =
                postJson("/api/v1/auth/refresh", Map.of("refreshToken", oldRefreshToken));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_ACCOUNT_INACTIVE");

        RefreshToken oldRow =
                refreshTokenRepository
                        .findByTokenHash(sha256(oldRefreshToken))
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "old refresh_tokens row was deleted — expected"
                                                        + " revoked_at to be set instead"));
        assertThat(oldRow.getRevokedAt())
                .as("old refresh token must have revoked_at set (durable revocation)")
                .isNotNull();
    }

    @Test
    void register_validPayload_returns201WithNoTokens() {
        String email = uniqueEmail("ok");
        ResponseEntity<Map> response =
                postJson("/api/v1/auth/register", registerBody("user_ok", email, TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("data")).isNull();
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        Integer outboxCount =
                jdbcTemplate.queryForObject(
                        """
						SELECT COUNT(*)
						FROM outbox_events
						WHERE aggregate_id = ?
						AND event_type IN (?, ?)
						AND payload::text NOT ILIKE '%token%'
						""",
                        Integer.class,
                        userId,
                        USER_REGISTERED_V1,
                        AUTH_EMAIL_VERIFICATION_REQUESTED_V1);
        assertThat(outboxCount).isEqualTo(2);
    }

    @Test
    void register_duplicateEmail_returns409() {
        String email = uniqueEmail("dup");
        postJson("/api/v1/auth/register", registerBody("user_dup1", email, TEST_PASSWORD));

        ResponseEntity<Map> second =
                postJson("/api/v1/auth/register", registerBody("user_dup2", email, TEST_PASSWORD));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void register_duplicateEmailDifferentCase_returns409() {
        String email = uniqueEmail("dupcase");
        postJson("/api/v1/auth/register", registerBody("user_dupcase1", email, TEST_PASSWORD));

        String upperCaseVariant =
                email.substring(0, email.indexOf('@')).toUpperCase()
                        + email.substring(email.indexOf('@'));
        ResponseEntity<Map> second =
                postJson(
                        "/api/v1/auth/register",
                        registerBody("user_dupcase2", upperCaseVariant, TEST_PASSWORD));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void register_invalidEmail_returns400() {
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/register",
                        registerBody("user_invalid", "not-an-email", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_shortPassword_returns400() {
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/register",
                        registerBody("user_short", uniqueEmail("short"), "short"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void login_unverifiedEmail_returns403() {
        String email = uniqueEmail("login_unverified");
        postJson("/api/v1/auth/register", registerBody("user_unverified", email, TEST_PASSWORD));

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_EMAIL_NOT_VERIFIED");
    }

    @Test
    void login_correctCredentials_returns200WithTokens() {
        String email = uniqueEmail("login_ok");
        postJson("/api/v1/auth/register", registerBody("user_login", email, TEST_PASSWORD));
        String token = createVerificationToken(email);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + token, Map.class);

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody().get("data")).get("accessToken"))
                .asString()
                .isNotBlank();
    }

    @Test
    void login_wrongPassword_returns401() {
        String email = uniqueEmail("wrong_pw");
        postJson("/api/v1/auth/register", registerBody("user_wpw", email, TEST_PASSWORD));

        ResponseEntity<Map> response =
                postJson("/api/v1/auth/login", Map.of("identifier", email, "password", "WRONG-PW"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void login_unknownEmail_returns401SameCodeAsWrongPassword() {
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", uniqueEmail("ghost"), "password", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void login_byUsername_correctCredentials_returns200WithTokens() {
        String email = uniqueEmail("uname_ok");
        String username = uniqueUsername("uok");
        postJson("/api/v1/auth/register", registerBody(username, email, TEST_PASSWORD));
        String token = createVerificationToken(email);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + token, Map.class);

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", username, "password", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody().get("data")).get("accessToken"))
                .asString()
                .isNotBlank();
    }

    @Test
    void login_byUsername_wrongPassword_returns401() {
        String email = uniqueEmail("uname_wpw");
        String username = uniqueUsername("uwpw");
        postJson("/api/v1/auth/register", registerBody(username, email, TEST_PASSWORD));

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", username, "password", "WRONG-PW"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void login_byUsername_unknownUsername_returns401SameCodeAsWrongPassword() {
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", uniqueUsername("ughost"), "password", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_INVALID_CREDENTIALS");
    }

    @Test
    void login_byUsername_upperCaseIdentifier_returns200() {
        String email = uniqueEmail("uname_upper");
        String username = uniqueUsername("uup");
        postJson("/api/v1/auth/register", registerBody(username, email, TEST_PASSWORD));
        String token = createVerificationToken(email);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + token, Map.class);

        // Username is stored lowercase; an uppercase submission must resolve to the same account.
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", username.toUpperCase(), "password", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody().get("data")).get("accessToken"))
                .asString()
                .isNotBlank();
    }

    @Test
    void login_byEmail_stillWorksAfterContractChange() {
        String email = uniqueEmail("email_still");
        String username = uniqueUsername("estl");
        postJson("/api/v1/auth/register", registerBody(username, email, TEST_PASSWORD));
        String token = createVerificationToken(email);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + token, Map.class);

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody().get("data")).get("accessToken"))
                .asString()
                .isNotBlank();
    }

    @Test
    void refresh_validToken_returns200WithNewPair() {
        String email = uniqueEmail("refresh_ok");
        Map<?, ?> sessionData = registerVerifyAndLogin("user_refr", email, TEST_PASSWORD);
        String refresh = (String) sessionData.get("refreshToken");

        ResponseEntity<Map> response =
                postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accessToken")).asString().isNotBlank();
        assertThat(data.get("refreshToken")).asString().isNotBlank();
        assertThat(data.get("refreshToken")).isNotEqualTo(refresh);
    }

    @Test
    void refresh_revokedToken_returns401() {
        String email = uniqueEmail("refresh_revoked");
        Map<?, ?> regData = registerVerifyAndLogin("user_rrv", email, TEST_PASSWORD);
        String refresh = (String) regData.get("refreshToken");
        String access = (String) regData.get("accessToken");
        postJsonWithAuth("/api/v1/auth/logout", Map.of("refreshToken", refresh), access);

        ResponseEntity<Map> response =
                postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @Test
    void refresh_alreadyUsedToken_returns401() {
        String email = uniqueEmail("refresh_used");
        String firstRefresh =
                (String)
                        registerVerifyAndLogin("user_rused", email, TEST_PASSWORD)
                                .get("refreshToken");
        // First rotation succeeds and revokes the original.
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", firstRefresh));

        // Replaying the original (now revoked) token must be rejected.
        ResponseEntity<Map> response =
                postJson("/api/v1/auth/refresh", Map.of("refreshToken", firstRefresh));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @Test
    void logout_returns204() {
        String email = uniqueEmail("logout");
        Map<?, ?> regData = registerVerifyAndLogin("user_lout", email, TEST_PASSWORD);
        String refresh = (String) regData.get("refreshToken");
        String access = (String) regData.get("accessToken");

        ResponseEntity<Map> response =
                postJsonWithAuth("/api/v1/auth/logout", Map.of("refreshToken", refresh), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void logout_noAuthHeader_returns401() {
        String email = uniqueEmail("logout_noauth");
        String refresh =
                (String)
                        registerVerifyAndLogin("user_lna", email, TEST_PASSWORD)
                                .get("refreshToken");

        ResponseEntity<Map> response =
                postJson("/api/v1/auth/logout", Map.of("refreshToken", refresh));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_validBearerToken_returns204() {
        String email = uniqueEmail("logout_auth");
        Map<?, ?> regData = registerVerifyAndLogin("user_lauth", email, TEST_PASSWORD);
        String refresh = (String) regData.get("refreshToken");
        String access = (String) regData.get("accessToken");

        ResponseEntity<Map> response =
                postJsonWithAuth("/api/v1/auth/logout", Map.of("refreshToken", refresh), access);

        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
    }

    @Test
    void login_noAuthHeader_isPubliclyReachable() {
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", uniqueEmail("login_pub"), "password", TEST_PASSWORD));

        // The endpoint is public; Spring Security must not block with 401-because-no-bearer.
        // A 401 here means bad credentials, not a missing token — that's still acceptable.
        assertThat(response.getStatusCode().value()).isNotEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void register_noAuthHeader_isPubliclyReachable() {
        String email = uniqueEmail("reg_pub");
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/register", registerBody("user_regpub", email, TEST_PASSWORD));

        assertThat(response.getStatusCode())
                .isIn(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void protectedEndpoint_validJwt_returns200() {
        String email = uniqueEmail("prot_ok");
        String access =
                (String)
                        registerVerifyAndLogin("user_prot", email, TEST_PASSWORD)
                                .get("accessToken");

        ResponseEntity<Map> response = getWithAuth("/api/v1/test/me", access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void protectedEndpoint_bannedUser_validAccessToken_returns401() {
        String email = uniqueEmail("prot_banned");
        String access =
                (String)
                        registerVerifyAndLogin("user_prtbn", email, TEST_PASSWORD)
                                .get("accessToken");

        userRepository
                .findByEmailAndDeletedAtIsNull(email)
                .ifPresent(
                        u -> {
                            u.setStatus(UserStatus.BANNED);
                            userRepository.save(u);
                        });

        ResponseEntity<Map> response = getWithAuth("/api/v1/test/me", access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_expiredJwt_returns401() {
        String expired = mintTestJwt(Instant.now().minusSeconds(60));

        ResponseEntity<Map> response = getWithAuth("/api/v1/test/me", expired);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_tamperedJwt_returns401() {
        String valid = mintTestJwt(Instant.now().plusSeconds(300));
        String[] parts = valid.split("\\.");
        char first = parts[2].charAt(0);
        char flipped = first == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + flipped + parts[2].substring(1);

        ResponseEntity<Map> response = getWithAuth("/api/v1/test/me", tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void verifyEmail_invalidToken_returnsBadRequest() {
        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/auth/verify-email?token=does-not-exist", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_VERIFY_TOKEN_INVALID");
    }

    @Test
    void verifyEmail_consumedToken_returnsBadRequest() {
        String email = uniqueEmail("consumed");
        ResponseEntity<Map> reg =
                postJson(
                        "/api/v1/auth/register",
                        registerBody("user_consumed", email, TEST_PASSWORD));
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String token = createVerificationToken(email);

        ResponseEntity<Map> first =
                rest.getForEntity("/api/v1/auth/verify-email?token=" + token, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> second =
                rest.getForEntity("/api/v1/auth/verify-email?token=" + token, Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody().get("code")).isEqualTo("AUTH_VERIFY_TOKEN_INVALID");
    }

    @Test
    void verifyEmail_unknownToken_returnsSameBadRequestAsConsumed() {
        ResponseEntity<Map> response =
                rest.getForEntity(
                        "/api/v1/auth/verify-email?token=" + UUID.randomUUID(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_VERIFY_TOKEN_INVALID");
    }

    @Test
    void verifyEmail_validToken_returns200WithTokens() {
        String email = uniqueEmail("verify_ok");
        postJson("/api/v1/auth/register", registerBody("user_vok", email, TEST_PASSWORD));
        String token = createVerificationToken(email);

        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/auth/verify-email?token=" + token, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accessToken")).asString().isNotBlank();
        assertThat(data.get("refreshToken")).asString().isNotBlank();
        assertThat(((Map<?, ?>) data.get("user")).get("emailVerified")).isEqualTo(true);
    }

    @Test
    void login_logout_reuseAccessToken_returns401() {
        String email = uniqueEmail("blacklist");
        Map<?, ?> data = registerVerifyAndLogin("user_bl", email, TEST_PASSWORD);
        String access = (String) data.get("accessToken");
        String refresh = (String) data.get("refreshToken");

        ResponseEntity<Map> meBefore = getWithAuth("/api/v1/test/me", access);
        assertThat(meBefore.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> logout =
                postJsonWithAuth("/api/v1/auth/logout", Map.of("refreshToken", refresh), access);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> reused = getWithAuth("/api/v1/test/me", access);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_doesNotInvalidateTheAccountsOtherSessions() {
        String email = uniqueEmail("multi_session");
        Map<?, ?> first = registerVerifyAndLogin("user_ms", email, TEST_PASSWORD);
        String firstAccess = (String) first.get("accessToken");
        String firstRefresh = (String) first.get("refreshToken");
        Map<?, ?> second =
                (Map<?, ?>)
                        postJson(
                                        "/api/v1/auth/login",
                                        Map.of("identifier", email, "password", TEST_PASSWORD))
                                .getBody()
                                .get("data");
        String secondAccess = (String) second.get("accessToken");
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();

        assertThat(
                        postJsonWithAuth(
                                        "/api/v1/auth/logout",
                                        Map.of("refreshToken", firstRefresh),
                                        firstAccess)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(getWithAuth("/api/v1/test/me", firstAccess).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getWithAuth("/api/v1/test/me", secondAccess).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT token_epoch FROM users WHERE id = ?",
                                Integer.class,
                                userId))
                .isZero();
    }

    @Test
    void login_wrongPassword_10timesSameIp_11thReturns429() {
        String forwardedIp = uniqueIp();
        String email = uniqueEmail("rl_login");
        postJson(
                "/api/v1/auth/register",
                registerBody("user_rllogin", email, TEST_PASSWORD),
                forwardedIp);

        for (int i = 0; i < 10; i++) {
            ResponseEntity<Map> response =
                    postJson(
                            "/api/v1/auth/login",
                            Map.of("identifier", email, "password", "WRONG"),
                            forwardedIp);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<Map> blocked =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", "WRONG"),
                        forwardedIp);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getBody().get("code")).isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void forgotPassword_3timesSameIp_4thReturns429() {
        String forwardedIp = uniqueIp();

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> response =
                    postJson(
                            "/api/v1/auth/forgot-password",
                            Map.of("email", uniqueEmail("rl_forgot")),
                            forwardedIp);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map> blocked =
                postJson(
                        "/api/v1/auth/forgot-password",
                        Map.of("email", uniqueEmail("rl_forgot")),
                        forwardedIp);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getBody().get("code")).isEqualTo("TOO_MANY_REQUESTS");
    }

    @Test
    void forgotPassword_unknownEmail_returns200NoLeak() {
        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/forgot-password",
                        Map.of("email", uniqueEmail("forgot_ghost")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void login_pastRateLimit_returns429WithRetryAfterHeader() {
        String forwardedIp = uniqueIp();
        String email = uniqueEmail("rl_retry_after");
        postJson(
                "/api/v1/auth/register",
                registerBody("user_rra", email, TEST_PASSWORD),
                forwardedIp);

        for (int i = 0; i < 10; i++) {
            postJson(
                    "/api/v1/auth/login",
                    Map.of("identifier", email, "password", "WRONG"),
                    forwardedIp);
        }

        ResponseEntity<Map> blocked =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", "WRONG"),
                        forwardedIp);

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        String retryAfter =
                blocked.getHeaders().getFirst(org.springframework.http.HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).as("429 response must include Retry-After header").isNotNull();
        assertThat(Long.parseLong(retryAfter))
                .as("Retry-After must be a positive number of seconds")
                .isGreaterThan(0);
    }

    @Test
    void login_bodyExceedsMaxLoginBodyBytes_returns400WithApiResponseEnvelope() {
        // Default maxLoginBodyBytes is 4096; build ~5KB of JSON padding.
        StringBuilder padding = new StringBuilder(5000);
        for (int i = 0; i < 5000; i++) {
            padding.append('x');
        }
        String body =
                "{\"email\":\"foo@example.com\",\"password\":\"p\",\"junk\":\"" + padding + "\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code")).isEqualTo("BAD_REQUEST");
        assertThat(response.getBody().get("success")).isEqualTo(false);
    }

    @Test
    void authController_hasNoRateLimiterAnnotations() {
        Method[] methods = AuthController.class.getDeclaredMethods();
        for (Method m : methods) {
            assertThat(
                            m.getAnnotation(
                                    io.github.resilience4j.ratelimiter.annotation.RateLimiter
                                            .class))
                    .as(
                            "Method %s should not carry @RateLimiter (now handled by"
                                    + " AuthRateLimitFilter)",
                            m.getName())
                    .isNull();
        }
    }

    @Test
    void refresh_emptyBodyWithCookie_returns200AndRotatesTheCookie() {
        String email = uniqueEmail("ck_refresh_ok");
        postJson("/api/v1/auth/register", registerBody("user_ckr", email, TEST_PASSWORD));
        rest.getForEntity(
                "/api/v1/auth/verify-email?token=" + createVerificationToken(email), Map.class);
        ResponseEntity<Map> login =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));
        String originalCookieValue = cookieValue(setCookie(login, "luvax_refresh"));

        ResponseEntity<Map> response =
                postJsonWithCookie(
                        "/api/v1/auth/refresh", Map.of(), "luvax_refresh=" + originalCookieValue);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accessToken")).asString().isNotBlank();
        String rotatedCookie = setCookie(response, "luvax_refresh");
        assertThat(rotatedCookie).isNotNull();
        assertThat(cookieValue(rotatedCookie)).isNotEqualTo(originalCookieValue);
        assertThat(cookieValue(rotatedCookie)).isEqualTo(data.get("refreshToken"));
    }

    @Test
    void refresh_bodyAndCookieBothPresent_bodyTokenTakesPrecedence() {
        String emailA = uniqueEmail("ck_prec_a");
        String emailB = uniqueEmail("ck_prec_b");
        String bodyToken =
                (String)
                        registerVerifyAndLogin("user_ckpa", emailA, TEST_PASSWORD)
                                .get("refreshToken");
        String cookieToken =
                (String)
                        registerVerifyAndLogin("user_ckpb", emailB, TEST_PASSWORD)
                                .get("refreshToken");

        ResponseEntity<Map> response =
                postJsonWithCookie(
                        "/api/v1/auth/refresh",
                        Map.of("refreshToken", bodyToken),
                        "luvax_refresh=" + cookieToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The body token belongs to user A, so precedence is proved by whose session came back.
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        Map<?, ?> user = (Map<?, ?>) data.get("user");
        assertThat(user.get("email")).isEqualTo(emailA);
        // The cookie token was never consumed, so it still rotates successfully afterwards.
        assertThat(
                        postJson("/api/v1/auth/refresh", Map.of("refreshToken", cookieToken))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void refresh_consumedTokenSuppliedByCookie_returns401() {
        String email = uniqueEmail("ck_reuse");
        String refresh =
                (String)
                        registerVerifyAndLogin("user_ckru", email, TEST_PASSWORD)
                                .get("refreshToken");
        postJson("/api/v1/auth/refresh", Map.of("refreshToken", refresh));

        ResponseEntity<Map> response =
                postJsonWithCookie("/api/v1/auth/refresh", Map.of(), "luvax_refresh=" + refresh);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @Test
    void refresh_noBodyTokenAndNoCookie_returns401() {
        ResponseEntity<Map> response = postJson("/api/v1/auth/refresh", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @Test
    void refresh_noBodyAtAllWithCookie_returns200AndRotatesTheCookie() {
        String email = uniqueEmail("nobody_refresh");
        postJson("/api/v1/auth/register", registerBody("user_nbr", email, TEST_PASSWORD));
        rest.getForEntity(
                "/api/v1/auth/verify-email?token=" + createVerificationToken(email), Map.class);
        ResponseEntity<Map> login =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));
        String originalCookieValue = cookieValue(setCookie(login, "luvax_refresh"));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "luvax_refresh=" + originalCookieValue);
        ResponseEntity<Map> response = postWithoutBody("/api/v1/auth/refresh", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("accessToken")).asString().isNotBlank();
        String rotatedCookie = setCookie(response, "luvax_refresh");
        assertThat(rotatedCookie).isNotNull();
        assertThat(cookieValue(rotatedCookie)).isNotEqualTo(originalCookieValue);
    }

    @Test
    void refresh_noBodyAtAllAndNoCookie_returns401() {
        ResponseEntity<Map> response = postWithoutBody("/api/v1/auth/refresh", new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @Test
    void logout_noBodyAtAllAndNoCookie_returns204AndStillClearsTheCookie() {
        String email = uniqueEmail("nobody_logout");
        String access =
                (String)
                        registerVerifyAndLogin("user_nbl", email, TEST_PASSWORD).get("accessToken");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(access);
        ResponseEntity<Map> response = postWithoutBody("/api/v1/auth/logout", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookie(response, "luvax_refresh")).isNotNull().contains("Max-Age=0");
    }

    @Test
    void logout_clearsTheCookieAndTheClearedCookieCannotRefresh() {
        String email = uniqueEmail("ck_logout");
        Map<?, ?> session = registerVerifyAndLogin("user_cklo", email, TEST_PASSWORD);
        String refresh = (String) session.get("refreshToken");
        String access = (String) session.get("accessToken");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(access);
        headers.add(HttpHeaders.COOKIE, "luvax_refresh=" + refresh);
        ResponseEntity<Map> logout =
                rest.exchange(
                        "/api/v1/auth/logout",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of(), headers),
                        Map.class);

        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String cleared = setCookie(logout, "luvax_refresh");
        assertThat(cleared).isNotNull();
        assertThat(cleared).contains("Max-Age=0");
        assertThat(cleared).contains("Path=/api/v1/auth");

        ResponseEntity<Map> afterLogout =
                postJsonWithCookie("/api/v1/auth/refresh", Map.of(), "luvax_refresh=" + refresh);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(afterLogout.getBody().get("code")).isEqualTo("AUTH_REFRESH_TOKEN_INVALID");
    }

    @Test
    void logout_noBodyTokenAndNoCookie_returns204AndStillClearsTheCookie() {
        // Logout is documented as idempotent. A client that lost its token value on reload must
        // still be able to clear its own cookie and blacklist its access token.
        String email = uniqueEmail("ck_logout_bare");
        String access =
                (String)
                        registerVerifyAndLogin("user_cklb", email, TEST_PASSWORD)
                                .get("accessToken");

        ResponseEntity<Map> response = postJsonWithAuth("/api/v1/auth/logout", Map.of(), access);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(setCookie(response, "luvax_refresh")).isNotNull().contains("Max-Age=0");
    }

    @Test
    void login_returnsHttpOnlyRefreshCookieScopedToTheAuthPath() {
        String email = uniqueEmail("cookie_login");
        postJson("/api/v1/auth/register", registerBody("user_ckl", email, TEST_PASSWORD));
        rest.getForEntity(
                "/api/v1/auth/verify-email?token=" + createVerificationToken(email), Map.class);

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));

        String cookie = setCookie(response, "luvax_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie).contains("HttpOnly");
        assertThat(cookie).contains("Path=/api/v1/auth");
        assertThat(cookie).contains("Max-Age=3600");
        assertThat(cookie).contains("SameSite=Lax");
        // The dev profile serves plain HTTP, where a Secure cookie would never be stored.
        assertThat(cookie).doesNotContain("Secure");
    }

    @Test
    void login_stillReturnsRefreshTokenInTheResponseBody() {
        String email = uniqueEmail("cookie_body");
        Map<?, ?> data = registerVerifyAndLogin("user_ckb", email, TEST_PASSWORD);

        assertThat(data.get("refreshToken")).asString().isNotBlank();
    }

    @Test
    void login_cookieValueMatchesTheResponseBodyToken() {
        String email = uniqueEmail("cookie_match");
        postJson("/api/v1/auth/register", registerBody("user_ckm", email, TEST_PASSWORD));
        rest.getForEntity(
                "/api/v1/auth/verify-email?token=" + createVerificationToken(email), Map.class);

        ResponseEntity<Map> response =
                postJson(
                        "/api/v1/auth/login",
                        Map.of("identifier", email, "password", TEST_PASSWORD));

        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(cookieValue(setCookie(response, "luvax_refresh")))
                .isEqualTo(data.get("refreshToken"));
    }

    @Test
    void verifyEmail_returnsRefreshCookieBecauseItIssuesASession() {
        String email = uniqueEmail("cookie_verify");
        postJson("/api/v1/auth/register", registerBody("user_ckv", email, TEST_PASSWORD));

        ResponseEntity<Map> response =
                rest.getForEntity(
                        "/api/v1/auth/verify-email?token=" + createVerificationToken(email),
                        Map.class);

        assertThat(setCookie(response, "luvax_refresh")).isNotNull().contains("HttpOnly");
    }

    @Test
    void exchangeOAuth2Code_returnsRefreshCookie() {
        String email = uniqueEmail("cookie_oauth");
        postJson("/api/v1/auth/register", registerBody("user_cko", email, TEST_PASSWORD));
        rest.getForEntity(
                "/api/v1/auth/verify-email?token=" + createVerificationToken(email), Map.class);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String code = oauth2ExchangeCodeService.storeExchangeCode(userId);

        ResponseEntity<Map> response =
                postJson("/api/v1/auth/oauth2/exchange", Map.of("code", code));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setCookie(response, "luvax_refresh")).isNotNull().contains("HttpOnly");
    }

    private ResponseEntity<Map> postJson(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> postJson(String path, Object body, String forwardedIp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedIp);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private static String setCookie(ResponseEntity<?> response, String name) {
        List<String> headers = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (headers == null) {
            return null;
        }
        return headers.stream().filter(h -> h.startsWith(name + "=")).findFirst().orElse(null);
    }

    private static String cookieValue(String setCookieHeader) {
        String nameValuePair = setCookieHeader.split(";", 2)[0];
        return nameValuePair.substring(nameValuePair.indexOf('=') + 1);
    }

    private ResponseEntity<Map> postJsonWithCookie(String path, Object body, String cookiePair) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add(HttpHeaders.COOKIE, cookiePair);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    /**
     * Issues a POST carrying no request body and no Content-Type at all, which is what an axios
     * call written as post(url, undefined, { withCredentials: true }) puts on the wire. This is
     * distinct from posting {}, which does carry a body and a Content-Type.
     */
    private ResponseEntity<Map> postWithoutBody(String path, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> postJsonWithAuth(String path, Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> getWithAuth(String path, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private Map<?, ?> registerVerifyAndLogin(String username, String email, String password) {
        postJson("/api/v1/auth/register", registerBody(username, email, password));
        String verToken = createVerificationToken(email);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + verToken, Map.class);
        ResponseEntity<Map> login =
                postJson("/api/v1/auth/login", Map.of("identifier", email, "password", password));
        return (Map<?, ?>) login.getBody().get("data");
    }

    private String createVerificationToken(String email) {
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        return tokenService.createEmailVerificationToken(userId);
    }

    private static Map<String, String> registerBody(
            String username, String email, String password) {
        return Map.of("username", username, "email", email, "password", password);
    }

    private static String uniqueEmail(String tag) {
        return tag + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private static String uniqueUsername(String tag) {
        // Lowercase, no '@', within the 30-char / [a-zA-Z0-9_.] username constraints.
        return tag + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static String uniqueIp() {
        // Build a 10.x.x.x address that is unique per test method so rate-limit buckets do not
        // bleed between scenarios. The window is 15 minutes so a fresh IP guarantees a clean
        // counter.
        java.util.Random rand = new java.util.Random();
        return "10." + rand.nextInt(256) + "." + rand.nextInt(256) + "." + (1 + rand.nextInt(254));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String mintTestJwt(Instant expiresAt) {
        SecretKey key =
                new SecretKeySpec(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        Instant issuedAt = expiresAt.minusSeconds(60);
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(TEST_JWT_ISSUER)
                        .audience(java.util.List.of(TEST_JWT_AUDIENCE))
                        .subject(UUID.randomUUID().toString())
                        .claim("email", "x@example.com")
                        .claim("role", "USER")
                        .issuedAt(issuedAt)
                        .notBefore(issuedAt)
                        .expiresAt(expiresAt)
                        .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @TestConfiguration
    static class IntegrationTestConfig {

        @Bean
        TestProtectedEndpoint testProtectedEndpoint() {
            return new TestProtectedEndpoint();
        }

        // httpclient5 honors Retry-After on 429; disable retries to prevent rate-limit-triggered
        // hangs.
        @Bean
        TestRestTemplate testRestTemplate(
                ObjectProvider<RestTemplateBuilder> builderProvider,
                ApplicationContext applicationContext) {
            RestTemplateBuilder builder =
                    builderProvider
                            .getIfAvailable(RestTemplateBuilder::new)
                            .requestFactoryBuilder(
                                    ClientHttpRequestFactoryBuilder.httpComponents()
                                            .withHttpClientCustomizer(
                                                    HttpClientBuilder::disableAutomaticRetries));
            LocalTestWebServer localTestWebServer = LocalTestWebServer.obtain(applicationContext);
            TestRestTemplate template = new TestRestTemplate(builder, null, null);
            template.setUriTemplateHandler(localTestWebServer.uriBuilderFactory());
            return template;
        }
    }

    @RestController
    @RequestMapping("/api/v1/test")
    static class TestProtectedEndpoint {

        @GetMapping("/me")
        ResponseEntity<ApiResponse<UUID>> me() {
            return ResponseEntity.ok(
                    ApiResponse.success(ApiSuccessCode.OK, SecurityUtils.getCurrentUserId()));
        }
    }
}
