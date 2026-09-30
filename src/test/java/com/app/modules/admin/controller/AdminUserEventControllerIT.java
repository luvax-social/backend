package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
class AdminUserEventControllerIT {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "admin-user-event-it-secret-32-chars-min!!");
        registry.add("JWT_ISSUER", () -> "https://adminuserevent.it.local");
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
        jdbcTemplate.update("DELETE FROM user_events");
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void listUserEvents_moderator_returnsForbidden() {
        TestUser moderator = createUser("evt_read_mod", "moderator");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ResponseEntity<Map> response = query(moderator, window(now.minusDays(7), now));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listUserEvents_validWindow_returnsTheEventsInside() {
        TestUser admin = createUser("evt_read_admin", "admin");
        TestUser subject = createUser("evt_read_subject", "user");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertEvent(subject.id(), "session_start", now.minusDays(1));
        insertEvent(subject.id(), "search", now.minusDays(2));
        insertEvent(subject.id(), "profile_view", now.minusDays(40));

        ResponseEntity<Map> response =
                query(admin, window(now.minusDays(7), now) + "&userId=" + subject.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventTypesOf(response)).containsExactly("session_start", "search");
    }

    @Test
    void listUserEvents_eventTypeFilter_narrowsToThatTypeAlone() {
        TestUser admin = createUser("evt_filter_admin", "admin");
        TestUser subject = createUser("evt_filter_subject", "user");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insertEvent(subject.id(), "session_start", now.minusDays(1));
        insertEvent(subject.id(), "search", now.minusDays(1));

        ResponseEntity<Map> response =
                query(admin, window(now.minusDays(7), now) + "&eventType=search");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(eventTypesOf(response)).containsExactly("search");
    }

    @Test
    void listUserEvents_absentFrom_isRejected() {
        TestUser admin = createUser("evt_nofrom_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ResponseEntity<Map> response = query(admin, "to=" + iso(now));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listUserEvents_absentTo_isRejected() {
        TestUser admin = createUser("evt_noto_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ResponseEntity<Map> response = query(admin, "from=" + iso(now.minusDays(1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listUserEvents_toNotAfterFrom_isRejected() {
        TestUser admin = createUser("evt_order_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(query(admin, window(now, now)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(query(admin, window(now, now.minusDays(1))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listUserEvents_windowLongerThanThirtyDays_isRejected() {
        TestUser admin = createUser("evt_span_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(query(admin, window(now.minusDays(31), now)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // Exactly thirty days is the boundary and must be accepted, so the rejection is proven to
        // be the span rule rather than an off-by-one in it.
        assertThat(query(admin, window(now.minusDays(30), now)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void listUserEvents_cursorFromAnotherSurface_isRejected() {
        TestUser admin = createUser("evt_scope_admin", "admin");
        insertAdminAction(admin.id());
        ResponseEntity<Map> actions = getWithAuth("/api/v1/admin/actions?limit=1", admin);
        String foreignCursor = (String) pageInfoOf(actions).get("endCursor");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ResponseEntity<Map> response =
                query(admin, window(now.minusDays(7), now) + "&cursor=" + foreignCursor);

        assertThat(foreignCursor).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listUserEvents_ownCursor_pagesWithoutRepeatingARow() {
        TestUser admin = createUser("evt_page_admin", "admin");
        TestUser subject = createUser("evt_page_subject", "user");
        OffsetDateTime tie = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
        for (int i = 0; i < 6; i++) {
            insertEvent(subject.id(), "search", tie);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ResponseEntity<Map> first = query(admin, window(now.minusDays(7), now) + "&limit=3");
        String cursor = (String) pageInfoOf(first).get("endCursor");
        ResponseEntity<Map> second =
                query(admin, window(now.minusDays(7), now) + "&limit=3&cursor=" + cursor);

        assertThat(idsOf(first)).hasSize(3);
        assertThat(idsOf(second)).hasSize(3);
        assertThat(idsOf(second)).doesNotContainAnyElementsOf(idsOf(first));
    }

    private static String window(OffsetDateTime from, OffsetDateTime to) {
        return "from=" + iso(from) + "&to=" + iso(to);
    }

    private static String iso(OffsetDateTime time) {
        return ISO.format(time).replace("+", "%2B");
    }

    private ResponseEntity<Map> query(TestUser user, String queryString) {
        return getWithAuth("/api/v1/admin/user-events?" + queryString, user);
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

    private void insertEvent(UUID userId, String eventType, OffsetDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO user_events (user_id, event_type, created_at) "
                        + "VALUES (?, CAST(? AS event_type), ?)",
                userId,
                eventType,
                createdAt);
    }

    private void insertAdminAction(UUID adminId) {
        jdbcTemplate.update(
                "INSERT INTO admin_actions (admin_id, action_type, reason) "
                        + "VALUES (?, CAST('ban_user' AS admin_action_type), 'seed')",
                adminId);
    }

    private ResponseEntity<Map> getWithAuth(String path, TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<String, Object>>) data.get("content");
    }

    private static List<String> eventTypesOf(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("eventType")).toList();
    }

    private static List<String> idsOf(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pageInfoOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (Map<String, Object>) data.get("pageInfo");
    }

    @Test
    void unauthenticatedRequest_isRejected() {
        assertThat(rest.getForEntity("/api/v1/admin/user-events", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void undeclaredQueryParameter_isRejected() {
        // The window is supplied so the only thing left to reject is the undeclared parameter. A
        // bare ?bogus=1 answers 400 for the missing window instead, which would pass whether the
        // parameter was rejected or silently ignored.
        TestUser admin = createUser("events_bogus_admin", "admin");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String window = "from=" + iso(now.minusDays(1)) + "&to=" + iso(now);

        assertThat(getWithAuth("/api/v1/admin/user-events?" + window, admin).getStatusCode())
                .as("the same request without the undeclared parameter is accepted")
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        getWithAuth("/api/v1/admin/user-events?" + window + "&bogus=1", admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
