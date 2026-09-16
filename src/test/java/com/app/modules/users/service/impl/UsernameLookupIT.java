package com.app.modules.users.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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

import com.app.common.enums.ApiErrorCode;
import com.app.common.exception.AppException;
import com.app.modules.mail.service.MailService;
import com.app.modules.users.dto.response.PublicUserProfileResponse;
import com.app.modules.users.service.UserService;

/**
 * Proves username identity is case-insensitive while stored casing is preserved for display, that
 * absence, soft deletion, and block-hiding are indistinguishable to the caller, and that a
 * UUID-shaped path segment cannot cross into the id-keyed endpoint.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class UsernameLookupIT {

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
        r.add("JWT_SECRET", () -> "username-lookup-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://username-lookup.it.local");
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

    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TestRestTemplate restTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM user_settings");
        // Registration writes credentials and an outbox event; both must go before users.
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void byUsername_exactMatch_returnsProfile() {
        UUID id = insertUser("jane_doe", false, false);

        PublicUserProfileResponse response = userService.getUserProfileByUsername(null, "jane_doe");

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.username()).isEqualTo("jane_doe");
    }

    @Test
    void byUsername_caseVariation_resolvesToTheSameAccount() {
        UUID id = insertUser("jane_doe", false, false);

        // Pins the case-insensitive identity contract established by idx_users_username_lower
        // (V42). One account owns every casing of its name, so any casing resolves to it. The
        // stored value is not normalized, so the response echoes the casing as registered rather
        // than the casing that was queried.
        PublicUserProfileResponse response = userService.getUserProfileByUsername(null, "Jane_Doe");

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.username()).isEqualTo("jane_doe");
    }

    @Test
    void insert_secondAccountDifferingOnlyByCase_isRejected() {
        insertUser("alice", false, false);

        // Deliberate inversion of a previously passing assertion. This test used to assert that
        // "alice" and "Alice" were two separate accounts, which idx_users_username_lower (V42)
        // now forbids by design: identity is case-insensitive, so they are one person.
        assertThatThrownBy(() -> insertUser("Alice", false, false))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void register_preservesSubmittedCasing() {
        // Goes through the registration service rather than a raw insert: the point of this test
        // is that the write path no longer lowercases, which a direct JDBC insert would not
        // exercise at all.
        assertThat(register("MixedCase", "mixed@example.com").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        String stored =
                jdbcTemplate.queryForObject(
                        "SELECT username FROM users WHERE lower(username) = 'mixedcase'",
                        String.class);

        // Display is case-preserving: nothing on the write path lowercases the stored value.
        assertThat(stored).isEqualTo("MixedCase");
    }

    @Test
    void byUsername_anyCasingOfAMixedCaseAccount_resolvesToThatRow() {
        UUID id = insertUser("MixedCase", false, false);

        for (String casing : new String[] {"mixedcase", "MIXEDCASE", "MixedCase"}) {
            PublicUserProfileResponse response = userService.getUserProfileByUsername(null, casing);
            assertThat(response.id()).as("lookup by %s", casing).isEqualTo(id);
            assertThat(response.username())
                    .as("stored casing echoed for %s", casing)
                    .isEqualTo("MixedCase");
        }
    }

    @Test
    void register_usernameHeldBySoftDeletedAccount_isRejected() {
        insertUser("ghostname", false, true);

        // GLOBAL_RULES section 3: soft delete does not release a username, and no purge job
        // exists. existsByUsername is table-wide precisely so this is caught in the service.
        ResponseEntity<String> conflict = register("GhostName", "new@example.com");

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("USER_ALREADY_EXISTS");
    }

    @Test
    void insert_usernameCollidingWithSoftDeletedAccount_isRejectedByConstraint() {
        insertUser("ghostname", false, true);

        // The database must enforce the same rule the service does, so a path that bypasses
        // existsByUsername cannot create the state GLOBAL_RULES section 3 forbids. Before V43
        // idx_users_username_lower was partial on deleted_at IS NULL and this insert succeeded.
        assertThatThrownBy(() -> insertUser("GhostName", false, false))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void register_usernameTakenInDifferentCase_returnsUserAlreadyExists() {
        assertThat(register("mixedcase", "first@example.com").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> conflict = register("MixedCase", "second@example.com");

        // The uniqueness pre-check compares case-insensitively, so a case variant is caught in
        // the service and reported as a normal conflict. Before that, it slipped past both the
        // pre-check and the in-transaction re-check and surfaced as a raw constraint violation.
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("USER_ALREADY_EXISTS");
    }

    @Test
    void register_takenUsernameAndTakenEmail_areIndistinguishable() {
        assertThat(register("mixedcase", "taken@example.com").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> usernameTaken = register("MixedCase", "fresh@example.com");
        ResponseEntity<String> emailTaken = register("freshname", "taken@example.com");

        // register() returns one generic conflict for both so the response cannot be used to
        // enumerate which of the two was already registered. A case-variant username previously
        // returned a different code and broke that property.
        assertThat(usernameTaken.getStatusCode()).isEqualTo(emailTaken.getStatusCode());
        assertThat(body(usernameTaken)).isEqualTo(body(emailTaken));
    }

    @Test
    void byUsername_nonExistent_returnsNotFound() {
        assertThatThrownBy(() -> userService.getUserProfileByUsername(null, "nobody_here"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void byUsername_softDeletedAccount_returnsNotFound() {
        insertUser("ghostuser", false, true);

        assertThatThrownBy(() -> userService.getUserProfileByUsername(null, "ghostuser"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void byUsername_blockedViewer_returnsNotFound() {
        UUID target = insertUser("target", false, false);
        UUID viewer = insertUser("viewer", false, false);
        block(target, viewer);

        assertThatThrownBy(() -> userService.getUserProfileByUsername(viewer, "target"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getErrorCode())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    @Test
    void byUsername_absentAndSoftDeleted_areIndistinguishableOverHttp() {
        insertUser("ghostuser", false, true);

        ResponseEntity<String> absent = get("nobody_here");
        ResponseEntity<String> deleted = get("ghostuser");

        assertThat(absent.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleted.getStatusCode()).isEqualTo(absent.getStatusCode());
        // Bodies must be identical apart from the per-response timestamp; any other divergence
        // would let an anonymous caller tell "no such account" from "account was deleted".
        assertThat(withoutTimestamp(deleted.getBody()))
                .isEqualTo(withoutTimestamp(absent.getBody()));
    }

    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<normalized>\"");
    }

    @Test
    void byUsername_hyphenatedUuid_isRejectedBeforeAnyLookup() {
        // A hyphenated UUID is 36 characters and contains '-', so it fails both the length ceiling
        // and the character class. It can never collide with a real username.
        ResponseEntity<String> response = get(UUID.randomUUID().toString());

        // Path and query constraint violations surface as HandlerMethodValidationException, which
        // GlobalExceptionHandler maps to 400. Only request-body violations return 422.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void byUsername_uuidShapedWithoutHyphens_doesNotResolveAnyUserById() {
        UUID id = insertUser("realuser", false, false);
        String hexOnly = id.toString().replace("-", "");

        // 32 hex characters passes the character class but exceeds the 30-character ceiling, so it
        // is rejected rather than being routed to the id-keyed endpoint.
        ResponseEntity<String> response = get(hexOnly);

        // Path and query constraint violations surface as HandlerMethodValidationException, which
        // GlobalExceptionHandler maps to 400. Only request-body violations return 422.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void byUsername_queryCountMatchesIdLookup() {
        UUID target = insertUser("target", false, false);
        UUID viewer = insertUser("viewer", false, false);

        Statistics stats = statistics();
        userService.getUserProfileByUsername(viewer, "target");

        stats.clear();
        userService.getUserProfile(viewer, target);
        long byId = stats.getPrepareStatementCount();

        stats.clear();
        userService.getUserProfileByUsername(viewer, "target");
        long byUsername = stats.getPrepareStatementCount();

        assertThat(byUsername).isEqualTo(byId);
    }

    private ResponseEntity<String> get(String username) {
        return restTemplate.getForEntity(
                "/api/v1/users/by-username/{username}", String.class, username);
    }

    private ResponseEntity<String> register(String username, String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload =
                """
				{"username":"%s","email":"%s","password":"Passw0rd!234","displayName":"%s"}"""
                        .formatted(username, email, username);
        return restTemplate.postForEntity(
                "/api/v1/auth/register", new HttpEntity<>(payload, headers), String.class);
    }

    /** Strips the timestamp so two conflict responses can be compared for equality. */
    private static String body(ResponseEntity<String> response) {
        return response.getBody() == null
                ? null
                : response.getBody().replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"*\"");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private UUID insertUser(String username, boolean isPrivate, boolean deleted) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_private, is_verified,"
                        + " deleted_at) VALUES (?, ?, ?, ?, false, ?) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username,
                isPrivate,
                deleted ? java.time.OffsetDateTime.now() : null);
    }

    private void block(UUID blocker, UUID blocked) {
        jdbcTemplate.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
    }
}
