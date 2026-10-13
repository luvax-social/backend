package com.app.modules.users.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
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
import com.app.testsupport.TestContainerImages;

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
class InteractionLimitControllerIT {

    private static final String TEST_PASSWORD = "S3cur3P@ssword";
    private static final String LIMITS = "/api/v1/users/me/interaction-limits";
    private static final String EVENT_TYPE = "user.interaction-limit.changed.v1";

    private static final String TEST_JWT_SECRET = "integration-test-secret-32-chars-minimum-len!!";
    private static final String TEST_JWT_ISSUER = "https://it.test.local";
    private static final String TEST_JWT_AUDIENCE = "App";

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
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;

    @Test
    void getLimits_neverSet_reportsBothTogglesOffAndInactive() {
        Account account = newAccount("il_none");

        ResponseEntity<Map> response = exchange(LIMITS, HttpMethod.GET, null, account);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = dataOf(response);
        assertThat(data.get("limitNonFollowers")).isEqualTo(false);
        assertThat(data.get("limitRecentFollowers")).isEqualTo(false);
        assertThat(data.get("expiresAt")).isNull();
        assertThat(data.get("active")).isEqualTo(false);
    }

    @Test
    void putLimits_sevenDays_storesTheExpiryServerSideAndReportsActive() {
        Account account = newAccount("il_put");
        OffsetDateTime before = OffsetDateTime.now();

        ResponseEntity<Map> response =
                exchange(
                        LIMITS,
                        HttpMethod.PUT,
                        Map.of(
                                "limitNonFollowers",
                                true,
                                "limitRecentFollowers",
                                false,
                                "durationDays",
                                7),
                        account);
        OffsetDateTime after = OffsetDateTime.now();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = dataOf(response);
        assertThat(data.get("limitNonFollowers")).isEqualTo(true);
        assertThat(data.get("limitRecentFollowers")).isEqualTo(false);
        assertThat(data.get("active")).isEqualTo(true);
        OffsetDateTime stored = storedExpiry(account.userId());
        assertThat(stored)
                .isBetween(before.plusDays(7).minusSeconds(5), after.plusDays(7).plusSeconds(5));
        assertThat(OffsetDateTime.parse((String) data.get("expiresAt")).toInstant())
                .isEqualTo(stored.toInstant());
        Map<?, ?> reread = dataOf(exchange(LIMITS, HttpMethod.GET, null, account));
        assertThat(reread.get("active")).isEqualTo(true);
        assertThat(reread.get("limitNonFollowers")).isEqualTo(true);
    }

    @Test
    void putLimits_everyPresetDuration_isAccepted() {
        Account account = newAccount("il_presets");

        for (int days : List.of(1, 3, 7, 14, 28)) {
            OffsetDateTime before = OffsetDateTime.now();
            ResponseEntity<Map> response = putLimits(account, true, true, days);

            assertThat(response.getStatusCode()).as("%s days", days).isEqualTo(HttpStatus.OK);
            assertThat(storedExpiry(account.userId()))
                    .as("%s days", days)
                    .isBetween(
                            before.plusDays(days).minusSeconds(5),
                            before.plusDays(days).plusSeconds(10));
        }
    }

    @Test
    void putLimits_secondCall_replacesTheSingleRow() {
        Account account = newAccount("il_replace");
        putLimits(account, true, false, 1);

        ResponseEntity<Map> response = putLimits(account, false, true, 28);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowCount(account.userId())).isEqualTo(1);
        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT limit_non_followers, limit_recent_followers FROM interaction_limits"
                                + " WHERE user_id = ?",
                        account.userId());
        assertThat(row.get("limit_non_followers")).isEqualTo(false);
        assertThat(row.get("limit_recent_followers")).isEqualTo(true);
        assertThat(storedExpiry(account.userId())).isAfter(OffsetDateTime.now().plusDays(27));
    }

    @Test
    void getLimits_rowPastItsExpiry_readsInactiveWithoutAnyJob() {
        Account account = newAccount("il_expired");
        jdbcTemplate.update(
                "INSERT INTO interaction_limits (user_id, limit_non_followers,"
                        + " limit_recent_followers, expires_at)"
                        + " VALUES (?, TRUE, FALSE, NOW() - INTERVAL '1 hour')",
                account.userId());

        Map<?, ?> data = dataOf(exchange(LIMITS, HttpMethod.GET, null, account));

        assertThat(data.get("active")).isEqualTo(false);
        assertThat(data.get("limitNonFollowers")).isEqualTo(true);
        assertThat(data.get("expiresAt")).isNotNull();
        assertThat(rowCount(account.userId())).isEqualTo(1);
    }

    @Test
    void putLimits_overAnExpiredRow_startsANewPeriod() {
        Account account = newAccount("il_expired_put");
        jdbcTemplate.update(
                "INSERT INTO interaction_limits (user_id, limit_non_followers,"
                        + " limit_recent_followers, expires_at)"
                        + " VALUES (?, TRUE, FALSE, NOW() - INTERVAL '1 hour')",
                account.userId());

        ResponseEntity<Map> response = putLimits(account, false, true, 3);

        assertThat(dataOf(response).get("active")).isEqualTo(true);
        assertThat(rowCount(account.userId())).isEqualTo(1);
    }

    @Test
    void deleteLimits_existingRow_returns204AndRemovesIt() {
        Account account = newAccount("il_delete");
        putLimits(account, true, true, 7);

        ResponseEntity<Map> deleted = exchange(LIMITS, HttpMethod.DELETE, null, account);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(account.userId())).isZero();
        Map<?, ?> data = dataOf(exchange(LIMITS, HttpMethod.GET, null, account));
        assertThat(data.get("active")).isEqualTo(false);
        assertThat(data.get("expiresAt")).isNull();
    }

    @Test
    void deleteLimits_nothingSet_returns204() {
        Account account = newAccount("il_delete_none");

        ResponseEntity<Map> response = exchange(LIMITS, HttpMethod.DELETE, null, account);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void putLimits_durationOutsideThePresets_returns400AndWritesNothing() {
        Account account = newAccount("il_bad_duration");

        for (int days : new int[] {0, 2, 5, 29, 30, -7}) {
            ResponseEntity<Map> response = putLimits(account, true, false, days);

            assertThat(response.getStatusCode())
                    .as("%s days", days)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(rowCount(account.userId())).isZero();
        assertThat(eventCount(account.userId())).isZero();
    }

    @Test
    void putLimits_bothTogglesOff_returns400AndWritesNothing() {
        Account account = newAccount("il_both_off");

        ResponseEntity<Map> response = putLimits(account, false, false, 7);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
        assertThat(rowCount(account.userId())).isZero();
        assertThat(eventCount(account.userId())).isZero();
    }

    @Test
    void putLimits_missingFields_returns400() {
        Account account = newAccount("il_missing");

        for (Map<String, Object> body :
                List.<Map<String, Object>>of(
                        Map.of("limitNonFollowers", true, "limitRecentFollowers", false),
                        Map.of("limitNonFollowers", true, "durationDays", 7),
                        Map.of("limitRecentFollowers", true, "durationDays", 7),
                        Map.of())) {
            ResponseEntity<Map> response = exchange(LIMITS, HttpMethod.PUT, body, account);

            assertThat(response.getStatusCode())
                    .as("body %s", body)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(rowCount(account.userId())).isZero();
    }

    @Test
    void putLimits_success_enqueuesOneChangeEventInTheSameTransaction() {
        Account account = newAccount("il_event_put");

        putLimits(account, true, false, 14);

        List<Map<String, Object>> events = eventsFor(account.userId());
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.get(0);
        assertThat(event.get("aggregate_type")).isEqualTo("user");
        assertThat(event.get("routing_key")).isEqualTo(EVENT_TYPE);
        assertThat(event.get("actor_id")).isEqualTo(account.userId().toString());
        assertThat(event.get("limit_non_followers")).isEqualTo("true");
        assertThat(event.get("limit_recent_followers")).isEqualTo("false");
        assertThat(OffsetDateTime.parse((String) event.get("expires_at")).toInstant())
                .isEqualTo(storedExpiry(account.userId()).toInstant());
    }

    @Test
    void deleteLimits_existingRow_enqueuesAChangeEventWithBothTogglesOffAndNoExpiry() {
        Account account = newAccount("il_event_delete");
        putLimits(account, true, true, 7);

        exchange(LIMITS, HttpMethod.DELETE, null, account);

        List<Map<String, Object>> events = eventsFor(account.userId());
        assertThat(events).hasSize(2);
        Map<String, Object> cleared = events.get(1);
        assertThat(cleared.get("limit_non_followers")).isEqualTo("false");
        assertThat(cleared.get("limit_recent_followers")).isEqualTo("false");
        assertThat(cleared.get("expires_at")).isNull();
    }

    @Test
    void deleteLimits_nothingSet_enqueuesNoEvent() {
        Account account = newAccount("il_event_noop");

        exchange(LIMITS, HttpMethod.DELETE, null, account);

        assertThat(eventCount(account.userId())).isZero();
    }

    @Test
    void limitEndpoints_withoutToken_return401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body =
                Map.of("limitNonFollowers", true, "limitRecentFollowers", false, "durationDays", 7);

        assertThat(
                        rest.exchange(LIMITS, HttpMethod.GET, new HttpEntity<>(headers), Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        LIMITS,
                                        HttpMethod.PUT,
                                        new HttpEntity<>(body, headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        LIMITS,
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(headers),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> putLimits(
            Account account, boolean nonFollowers, boolean recentFollowers, int days) {
        return exchange(
                LIMITS,
                HttpMethod.PUT,
                Map.of(
                        "limitNonFollowers",
                        nonFollowers,
                        "limitRecentFollowers",
                        recentFollowers,
                        "durationDays",
                        days),
                account);
    }

    private OffsetDateTime storedExpiry(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT expires_at FROM interaction_limits WHERE user_id = ?",
                OffsetDateTime.class,
                userId);
    }

    private int rowCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM interaction_limits WHERE user_id = ?", Integer.class, userId);
    }

    private int eventCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Integer.class,
                EVENT_TYPE,
                userId);
    }

    private List<Map<String, Object>> eventsFor(UUID userId) {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_type, routing_key, payload->>'actorId' AS actor_id,"
                        + " payload->'data'->>'limitNonFollowers' AS limit_non_followers,"
                        + " payload->'data'->>'limitRecentFollowers' AS limit_recent_followers,"
                        + " payload->'data'->>'expiresAt' AS expires_at"
                        + " FROM outbox_events WHERE event_type = ? AND aggregate_id = ?"
                        + " ORDER BY created_at, id",
                EVENT_TYPE,
                userId);
    }

    private static Map<?, ?> dataOf(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    private Account newAccount(String tag) {
        String email = tag + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String username = tag + UUID.randomUUID().toString().substring(0, 6);
        exchange(
                "/api/v1/auth/register",
                HttpMethod.POST,
                Map.of("username", username, "email", email, "password", TEST_PASSWORD),
                null);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        String verToken = tokenService.createEmailVerificationToken(userId);
        rest.getForEntity("/api/v1/auth/verify-email?token=" + verToken, Map.class);
        ResponseEntity<Map> login =
                exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        Map.of("identifier", email, "password", TEST_PASSWORD),
                        null);
        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        return new Account(userId, (String) data.get("accessToken"));
    }

    private ResponseEntity<Map> exchange(
            String path, HttpMethod method, Object body, Account account) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (account != null) {
            headers.setBearerAuth(account.accessToken());
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }

    private record Account(UUID userId, String accessToken) {}
}
