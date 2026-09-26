package com.app.common.vocabulary.controller;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.mail.service.MailService;

/**
 * Exercises the read-only vocabulary surface.
 *
 * <p>The behaviour that matters is the enabled flag. A client that never sees a disabled reason
 * keeps offering it, the user picks it, and the server refuses something the interface said was
 * valid. So a disabled row must come back present and flagged, not filtered out, and the write path
 * must still refuse it.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.vocabulary.public-categories-cache-ttl=0s",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class VocabularyControllerIT {

    private static final String PATH = "/api/v1/config/vocabularies";
    private static final String PUBLIC_CATEGORIES_PATH = "/api/v1/support/public/categories";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "vocabulary-it-secret-32-chars-minimum!!!!");
        registry.add("JWT_ISSUER", () -> "https://vocabulary.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("UPDATE report_reason_configs SET is_enabled = TRUE");
        jdbcTemplate.update("UPDATE support_category_configs SET is_enabled = TRUE");
        jdbcTemplate.update("DELETE FROM user_warnings");
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void getVocabularies_ordinaryUser_returnsAllThreeTables() {
        // Readable by any authenticated caller on purpose: an ordinary user submitting a report
        // needs the same reason list a moderator needs when issuing a warning.
        TestUser ordinary = createUser("vocab_user", "user");

        ResponseEntity<Map> response = getWithAuth(ordinary);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(data).containsKeys("reportReasons", "notificationTypes", "moderationActions");
        assertThat(listOf(data, "reportReasons")).isNotEmpty();
        assertThat(listOf(data, "notificationTypes")).isNotEmpty();
        assertThat(listOf(data, "moderationActions")).isNotEmpty();
    }

    @Test
    void getVocabularies_everyRoleReadsTheSameThing() {
        TestUser ordinary = createUser("vocab_role_user", "user");
        TestUser moderator = createUser("vocab_role_mod", "moderator");
        TestUser admin = createUser("vocab_role_admin", "admin");

        assertThat(dataOf(getWithAuth(ordinary)))
                .isEqualTo(dataOf(getWithAuth(moderator)))
                .isEqualTo(dataOf(getWithAuth(admin)));
    }

    @Test
    void getVocabularies_unauthenticated_isRejected() {
        assertThat(rest.getForEntity(PATH, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // P7-BE-004 and the section 4.9 gap. This route is the one vocabulary read that must work with
    // no session at all: the submitter using the public support form has none, and that is the
    // premise of the whole path. Asserted rather than assumed, because the route sitting outside
    // the authenticated tree is a security decision that a later change to the filter chain could
    // silently reverse.
    @Test
    void getPublicSupportCategories_withNoSession_isServed() {
        ResponseEntity<Map> response = rest.getForEntity(PUBLIC_CATEGORIES_PATH, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicCategories(response)).isNotEmpty();
    }

    // The filter predicate itself, which had no test. It is the rule that decides what an anonymous
    // form may offer, and since this branch it is also the rule that decides what the submit path
    // accepts, so both halves now move together.
    @Test
    void getPublicSupportCategories_returnsOnlyEnabledPublicFormRows() {
        List<Map<String, Object>> rows =
                publicCategories(rest.getForEntity(PUBLIC_CATEGORIES_PATH, Map.class));

        assertThat(rows).isNotEmpty();
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("isEnabled")).isEqualTo(true);
                            assertThat(row.get("allowsPublicForm")).isEqualTo(true);
                        });
        // Appeal categories are excluded by that flag, which is the point: an appeal needs an audit
        // row to appeal against, and only a signed link supplies one.
        assertThat(rows).noneSatisfy(row -> assertThat(row.get("isAppeal")).isEqualTo(true));
    }

    @Test
    void getPublicSupportCategories_disablingARow_removesItFromTheList() {
        List<Map<String, Object>> before =
                publicCategories(rest.getForEntity(PUBLIC_CATEGORIES_PATH, Map.class));
        assertThat(keysOf(before)).contains("bug_report");

        jdbcTemplate.update(
                "UPDATE support_category_configs SET is_enabled = FALSE WHERE category_key ="
                        + " 'bug_report'");

        assertThat(keysOf(publicCategories(rest.getForEntity(PUBLIC_CATEGORIES_PATH, Map.class))))
                .doesNotContain("bug_report");
    }

    @Test
    void getVocabularies_everyReportReasonCarriesItsDisplayMetadata() {
        TestUser ordinary = createUser("vocab_meta_user", "user");

        Map<String, Object> reason =
                listOf(dataOf(getWithAuth(ordinary)), "reportReasons").stream()
                        .filter(row -> "hate_speech".equals(row.get("key")))
                        .findFirst()
                        .orElseThrow();

        assertThat(reason.get("displayName")).isEqualTo("Hate Speech");
        assertThat(reason.get("isEnabled")).isEqualTo(true);
        assertThat(reason.get("sortOrder")).isEqualTo(4);
        assertThat(reason.get("appliesTo"))
                .isEqualTo(List.of("post", "comment", "story", "message"));
    }

    @Test
    void getVocabularies_reportReasonsComeBackInDisplayOrder() {
        TestUser ordinary = createUser("vocab_order_user", "user");

        List<Object> sortOrders =
                listOf(dataOf(getWithAuth(ordinary)), "reportReasons").stream()
                        .map(row -> row.get("sortOrder"))
                        .toList();

        assertThat(sortOrders).isSorted();
    }

    @Test
    void getVocabularies_disabledReason_isPresentAndFlaggedRatherThanFilteredOut() {
        // Filtering it out would leave a client unable to tell a reason an administrator switched
        // off from one that never existed, and a label it had already rendered would disappear.
        TestUser ordinary = createUser("vocab_disabled_user", "user");
        jdbcTemplate.update(
                "UPDATE report_reason_configs SET is_enabled = FALSE WHERE reason_key = 'scam'");

        Map<String, Object> reason =
                listOf(dataOf(getWithAuth(ordinary)), "reportReasons").stream()
                        .filter(row -> "scam".equals(row.get("key")))
                        .findFirst()
                        .orElseThrow();

        assertThat(reason.get("isEnabled")).isEqualTo(false);
    }

    @Test
    void getVocabularies_moderationActionsCarryTheirBehaviouralFlags() {
        TestUser ordinary = createUser("vocab_action_user", "user");

        Map<String, Object> action =
                listOf(dataOf(getWithAuth(ordinary)), "moderationActions").stream()
                        .filter(row -> "ban_user".equals(row.get("key")))
                        .findFirst()
                        .orElseThrow();

        assertThat(action.get("displayName")).isEqualTo("Ban User");
        assertThat(action.get("requiresReason")).isEqualTo(true);
        assertThat(action.get("isReversible")).isEqualTo(false);
        assertThat(action.get("isEnabled")).isEqualTo(true);
    }

    @Test
    void getVocabularies_notificationTypesCarryTheirToggleFlag() {
        TestUser ordinary = createUser("vocab_notif_user", "user");
        List<Map<String, Object>> types =
                listOf(dataOf(getWithAuth(ordinary)), "notificationTypes");

        Map<String, Object> warning =
                types.stream()
                        .filter(row -> "warning".equals(row.get("key")))
                        .findFirst()
                        .orElseThrow();
        Map<String, Object> like =
                types.stream()
                        .filter(row -> "like_post".equals(row.get("key")))
                        .findFirst()
                        .orElseThrow();

        // A moderation warning is not toggleable: an account that could silence it would be
        // disciplined without ever being told.
        assertThat(warning.get("isUserToggleable")).isEqualTo(false);
        assertThat(like.get("isUserToggleable")).isEqualTo(true);
    }

    @Test
    void getVocabularies_undeclaredQueryParameter_isRejected() {
        TestUser ordinary = createUser("vocab_bogus_user", "user");

        assertThat(getWithAuth(ordinary, "?bogus=1").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private TestUser createUser(String username, String role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local",
                role);
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private ResponseEntity<Map> getWithAuth(TestUser user) {
        return getWithAuth(user, "");
    }

    private ResponseEntity<Map> getWithAuth(TestUser user, String queryString) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                PATH + queryString, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> data, String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> publicCategories(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private static List<String> keysOf(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> (String) row.get("categoryKey")).toList();
    }
}
