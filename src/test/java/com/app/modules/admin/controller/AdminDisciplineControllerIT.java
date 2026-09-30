package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

/**
 * End-to-end coverage of the warning and strike ladder.
 *
 * <p>The notification is asserted as an {@code outbox_events} row rather than a {@code
 * notifications} row: the outbox publisher and the RabbitMQ listener are both off in this context,
 * so the enqueue is the last step that happens in-process. The row itself is verified in the live
 * run, where the broker is real.
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
class AdminDisciplineControllerIT {

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
        registry.add("JWT_SECRET", () -> "admin-discipline-it-secret-32-chars-minimum!!");
        registry.add("JWT_ISSUER", () -> "https://admin-discipline.it.local");
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
        registry.add("app.admin.suspension-expiry.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM user_strikes");
        jdbcTemplate.update("DELETE FROM user_warnings");
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM notifications");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void warnUser_firstWarning_recordsTheRowTheAuditRowAndTheNotification() {
        TestUser moderator = createUser("warn_mod", "moderator");
        TestUser target = createUser("warn_target", "user");

        ResponseEntity<Map> response = warn(moderator, target, "spam", "Bulk direct messages");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("strikeIssued")).isEqualTo(false);
        assertThat(data.get("activeWarningCount")).isEqualTo(1);
        assertThat(data.get("resultingStatus")).isEqualTo("active");

        Map<String, Object> warning =
                jdbcTemplate.queryForMap(
                        "SELECT reason_key, note, issued_by, admin_action_id FROM user_warnings"
                                + " WHERE user_id = ?",
                        target.id());
        assertThat(warning.get("reason_key")).isEqualTo("spam");
        assertThat(warning.get("note")).isEqualTo("Bulk direct messages");
        assertThat(warning.get("issued_by")).isEqualTo(moderator.id());
        assertThat(warning.get("admin_action_id")).isNotNull();
        assertThat(auditCount("warn_user", target.id())).isOne();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events"
                                        + " WHERE event_type = 'user.warned.v1'"
                                        + " AND aggregate_id = ?",
                                Integer.class,
                                target.id()))
                .isOne();
    }

    @Test
    void warnUser_thirdWarning_issuesStrikeOneAndSuspendsForSevenDays() {
        TestUser moderator = createUser("s1_mod", "moderator");
        TestUser target = createUser("s1_target", "user");

        warn(moderator, target, "spam", "One");
        warn(moderator, target, "spam", "Two");
        ResponseEntity<Map> third = warn(moderator, target, "spam", "Three");

        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(third).get("strikeIssued")).isEqualTo(true);
        assertThat(dataOf(third).get("activeWarningCount")).isEqualTo(0);
        assertThat(dataOf(third).get("resultingStatus")).isEqualTo("suspended");

        assertThat(strikeNumbers(target.id())).containsExactly(1);
        assertThat(statusOf(target.id())).isEqualTo("suspended");
        assertThat(suspendedUntilOf(target.id()))
                .isCloseTo(
                        OffsetDateTime.now().plusDays(7),
                        org.assertj.core.api.Assertions.within(
                                10, java.time.temporal.ChronoUnit.MINUTES));

        // Two audit rows: the moderator's warning, and the strike, whose actor is null because the
        // ladder issued it rather than a person.
        assertThat(auditCount("warn_user", target.id())).isEqualTo(3);
        assertThat(auditCount("issue_strike", target.id())).isOne();
        Map<String, Object> strikeAudit =
                jdbcTemplate.queryForMap(
                        "SELECT admin_id, metadata::text AS metadata FROM admin_actions"
                                + " WHERE action_type = 'issue_strike' AND target_user_id = ?",
                        target.id());
        assertThat(strikeAudit.get("admin_id")).isNull();
        assertThat((String) strikeAudit.get("metadata"))
                .contains("triggeredByModeratorId")
                .contains(moderator.id().toString())
                .contains("\"strikeNumber\": 1");
    }

    @Test
    void warnUser_sixthWarning_issuesStrikeTwoAndSuspendsForThirtyDays() {
        TestUser moderator = createUser("s2_mod", "moderator");
        TestUser target = createUser("s2_target", "user");

        warnTimes(moderator, target, 6);

        assertThat(strikeNumbers(target.id())).containsExactly(1, 2);
        assertThat(statusOf(target.id())).isEqualTo("suspended");
        assertThat(suspendedUntilOf(target.id()))
                .isCloseTo(
                        OffsetDateTime.now().plusDays(30),
                        org.assertj.core.api.Assertions.within(
                                10, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    void warnUser_ninthWarning_issuesStrikeThreeAndBansPermanently() {
        TestUser moderator = createUser("s3_mod", "moderator");
        TestUser target = createUser("s3_target", "user");

        warnTimes(moderator, target, 9);

        assertThat(strikeNumbers(target.id())).containsExactly(1, 2, 3);
        assertThat(statusOf(target.id())).isEqualTo("banned");
        assertThat(suspendedUntilOf(target.id())).isNull();
    }

    @Test
    void warnUser_afterAManualUnban_insertsAFourthStrikeAndBansAgain() {
        TestUser moderator = createUser("s4_mod", "moderator");
        TestUser target = createUser("s4_target", "user");
        warnTimes(moderator, target, 9);
        jdbcTemplate.update("UPDATE users SET status = 'active' WHERE id = ?", target.id());

        warnTimes(moderator, target, 3);

        assertThat(strikeNumbers(target.id())).containsExactly(1, 2, 3, 4);
        assertThat(statusOf(target.id())).isEqualTo("banned");
    }

    @Test
    void warnUser_warningOlderThanNinetyDays_doesNotCount() {
        TestUser moderator = createUser("age_mod", "moderator");
        TestUser target = createUser("age_target", "user");
        warn(moderator, target, "spam", "One");
        warn(moderator, target, "spam", "Two");
        jdbcTemplate.update(
                "UPDATE user_warnings SET created_at = NOW() - INTERVAL '100 days'"
                        + " WHERE user_id = ?",
                target.id());

        ResponseEntity<Map> third = warn(moderator, target, "spam", "Three");

        assertThat(dataOf(third).get("strikeIssued")).isEqualTo(false);
        assertThat(dataOf(third).get("activeWarningCount")).isEqualTo(1);
        assertThat(statusOf(target.id())).isEqualTo("active");
    }

    @Test
    void warnUser_strikeConsequenceWeakerThanCurrentState_leavesTheStateAndStillWritesTheStrike() {
        TestUser moderator = createUser("p5_mod", "moderator");
        TestUser target = createUser("p5_target", "user");
        jdbcTemplate.update(
                "UPDATE users SET status = 'suspended', suspended_until = NULL WHERE id = ?",
                target.id());

        warnTimes(moderator, target, 3);

        assertThat(strikeNumbers(target.id())).containsExactly(1);
        assertThat(statusOf(target.id())).isEqualTo("suspended");
        assertThat(suspendedUntilOf(target.id())).isNull();
    }

    @Test
    void warnUser_moderatorTarget_returnsForbidden() {
        TestUser moderator = createUser("prot_mod", "moderator");
        TestUser other = createUser("prot_other_mod", "moderator");

        ResponseEntity<Map> response = warn(moderator, other, "spam", "note");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_TARGET_NOT_WARNABLE");
        assertThat(warningCount(other.id())).isZero();
    }

    @Test
    void warnUser_administratorTarget_returnsForbidden() {
        TestUser moderator = createUser("prot_mod2", "moderator");
        TestUser admin = createUser("prot_admin", "admin");

        ResponseEntity<Map> response = warn(moderator, admin, "spam", "note");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(warningCount(admin.id())).isZero();
    }

    @Test
    void warnUser_selfTarget_returnsConflict() {
        TestUser moderator = createUser("self_mod", "moderator");

        ResponseEntity<Map> response = warn(moderator, moderator, "spam", "note");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_SELF_ACTION_NOT_ALLOWED");
    }

    @Test
    void warnUser_disabledReason_returnsUnprocessableEntity() {
        TestUser moderator = createUser("reason_mod", "moderator");
        TestUser target = createUser("reason_target", "user");
        jdbcTemplate.update(
                "UPDATE report_reason_configs SET is_enabled = FALSE WHERE reason_key = 'scam'");
        try {
            ResponseEntity<Map> response = warn(moderator, target, "scam", "note");

            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getBody().get("code")).isEqualTo("WARNING_REASON_DISABLED");
            assertThat(warningCount(target.id())).isZero();
        } finally {
            jdbcTemplate.update(
                    "UPDATE report_reason_configs SET is_enabled = TRUE WHERE reason_key = 'scam'");
        }
    }

    @Test
    void warnUser_regularUserActor_returnsForbidden() {
        TestUser actor = createUser("plain_actor", "user");
        TestUser target = createUser("plain_target", "user");

        assertThat(warn(actor, target, "spam", "note").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void warnUser_twoConcurrentThirdWarnings_produceExactlyOneStrike() throws Exception {
        TestUser moderator = createUser("race_mod", "moderator");
        TestUser other = createUser("race_mod2", "moderator");
        TestUser target = createUser("race_target", "user");
        warn(moderator, target, "spam", "One");
        warn(moderator, target, "spam", "Two");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<ResponseEntity<Map>> attempt =
                () -> {
                    ready.countDown();
                    go.await();
                    return warn(moderator, target, "spam", "Concurrent");
                };
        Callable<ResponseEntity<Map>> otherAttempt =
                () -> {
                    ready.countDown();
                    go.await();
                    return warn(other, target, "spam", "Concurrent");
                };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> first = pool.submit(attempt);
            Future<ResponseEntity<Map>> second = pool.submit(otherAttempt);
            ready.await();
            go.countDown();
            ResponseEntity<Map> firstResult = first.get();
            ResponseEntity<Map> secondResult = second.get();

            assertThat(firstResult.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(secondResult.getStatusCode()).isEqualTo(HttpStatus.OK);
        } finally {
            pool.shutdownNow();
        }

        assertThat(warningCount(target.id())).isEqualTo(4);
        assertThat(strikeNumbers(target.id())).containsExactly(1);
        assertThat(auditCount("issue_strike", target.id())).isOne();
    }

    @Test
    void revokeWarning_leavesTheStrikeAndTheSuspensionIntact() {
        TestUser admin = createUser("rw_admin", "admin");
        TestUser moderator = createUser("rw_mod", "moderator");
        TestUser target = createUser("rw_target", "user");
        warnTimes(moderator, target, 3);
        UUID warningId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM user_warnings WHERE user_id = ? ORDER BY created_at LIMIT 1",
                        UUID.class,
                        target.id());

        ResponseEntity<Map> response =
                delete(
                        "/api/v1/admin/warnings/" + warningId,
                        Map.of("reason", "Issued in error"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("actionType")).isEqualTo("revoke_warning");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT revoked_by FROM user_warnings WHERE id = ?",
                                UUID.class,
                                warningId))
                .isEqualTo(admin.id());
        assertThat(strikeNumbers(target.id())).containsExactly(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM user_strikes"
                                        + " WHERE user_id = ? AND revoked_at IS NULL",
                                Integer.class,
                                target.id()))
                .isOne();
        assertThat(statusOf(target.id())).isEqualTo("suspended");
    }

    @Test
    void revokeWarning_alreadyRevoked_returnsConflict() {
        TestUser admin = createUser("rw2_admin", "admin");
        TestUser moderator = createUser("rw2_mod", "moderator");
        TestUser target = createUser("rw2_target", "user");
        warn(moderator, target, "spam", "One");
        UUID warningId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM user_warnings WHERE user_id = ?", UUID.class, target.id());
        delete("/api/v1/admin/warnings/" + warningId, Map.of("reason", "First"), admin);

        ResponseEntity<Map> response =
                delete("/api/v1/admin/warnings/" + warningId, Map.of("reason", "Again"), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void revokeWarning_moderatorActor_returnsForbidden() {
        TestUser moderator = createUser("rw3_mod", "moderator");
        TestUser target = createUser("rw3_target", "user");
        warn(moderator, target, "spam", "One");
        UUID warningId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM user_warnings WHERE user_id = ?", UUID.class, target.id());

        ResponseEntity<Map> response =
                delete("/api/v1/admin/warnings/" + warningId, Map.of("reason", "Nope"), moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void revokeStrike_leavesTheAccountStatusUntouched() {
        TestUser admin = createUser("rs_admin", "admin");
        TestUser moderator = createUser("rs_mod", "moderator");
        TestUser target = createUser("rs_target", "user");
        warnTimes(moderator, target, 3);
        UUID strikeId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM user_strikes WHERE user_id = ?", UUID.class, target.id());

        ResponseEntity<Map> response =
                delete(
                        "/api/v1/admin/strikes/" + strikeId,
                        Map.of("reason", "Appeal upheld"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("actionType")).isEqualTo("revoke_strike");
        assertThat(statusOf(target.id())).isEqualTo("suspended");
        assertThat(suspendedUntilOf(target.id())).isNotNull();
    }

    @Test
    void listViolations_moderator_seesWarningsOnly() {
        TestUser moderator = createUser("lv_mod", "moderator");
        TestUser target = createUser("lv_target", "user");
        warnTimes(moderator, target, 3);

        List<Map<String, Object>> content = contentOf(get(violationsPath(target), moderator));

        assertThat(content).hasSize(3);
        assertThat(content).allSatisfy(row -> assertThat(row.get("kind")).isEqualTo("warning"));
        assertThat(content).allSatisfy(row -> assertThat(row.get("strikeNumber")).isNull());
    }

    @Test
    void listViolations_administrator_seesWarningsAndStrikes() {
        TestUser admin = createUser("lv2_admin", "admin");
        TestUser moderator = createUser("lv2_mod", "moderator");
        TestUser target = createUser("lv2_target", "user");
        warnTimes(moderator, target, 3);

        List<Map<String, Object>> content = contentOf(get(violationsPath(target), admin));

        assertThat(content).hasSize(4);
        assertThat(content.stream().filter(row -> "strike".equals(row.get("kind"))).count())
                .isOne();
    }

    @Test
    void listViolations_moderatorReplayingAnAdministratorCursor_isRejected() {
        TestUser admin = createUser("cur_admin", "admin");
        TestUser moderator = createUser("cur_mod", "moderator");
        TestUser target = createUser("cur_target", "user");
        warnTimes(moderator, target, 3);
        ResponseEntity<Map> adminPage = get(violationsPath(target) + "?limit=1", admin);
        String adminCursor = (String) pageInfoOf(adminPage).get("endCursor");
        assertThat(adminCursor).isNotBlank();

        ResponseEntity<Map> replayed =
                get(violationsPath(target) + "?limit=1&cursor=" + adminCursor, moderator);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(replayed.getBody().get("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void listOwnWarnings_returnsOwnWarningsAndNeverAnotherAccounts() {
        TestUser moderator = createUser("own_mod", "moderator");
        TestUser first = createUser("own_first", "user");
        TestUser second = createUser("own_second", "user");
        warn(moderator, first, "spam", "First account");
        warn(moderator, second, "harassment", "Second account");

        List<Map<String, Object>> mine = contentOf(get("/api/v1/users/me/warnings", first));

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("note")).isEqualTo("First account");
        assertThat(mine.get(0)).doesNotContainKey("issuedBy");
        assertThat(mine.get(0)).doesNotContainKey("strikeNumber");

        List<Map<String, Object>> theirs = contentOf(get("/api/v1/users/me/warnings", second));
        assertThat(theirs).hasSize(1);
        assertThat(theirs.get(0).get("note")).isEqualTo("Second account");
    }

    @Test
    void listOwnWarnings_revokedWarning_isNotReturned() {
        TestUser admin = createUser("own2_admin", "admin");
        TestUser moderator = createUser("own2_mod", "moderator");
        TestUser target = createUser("own2_target", "user");
        warn(moderator, target, "spam", "Only warning");
        UUID warningId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM user_warnings WHERE user_id = ?", UUID.class, target.id());
        delete("/api/v1/admin/warnings/" + warningId, Map.of("reason", "Withdrawn"), admin);

        assertThat(contentOf(get("/api/v1/users/me/warnings", target))).isEmpty();
    }

    private void warnTimes(TestUser actor, TestUser target, int times) {
        for (int i = 0; i < times; i++) {
            ResponseEntity<Map> response = warn(actor, target, "spam", "Warning " + (i + 1));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private ResponseEntity<Map> warn(
            TestUser actor, TestUser target, String reasonKey, String note) {
        return post(
                "/api/v1/admin/warnings/for-user/" + target.id(),
                Map.of("reasonKey", reasonKey, "note", note),
                actor);
    }

    private static String violationsPath(TestUser target) {
        return "/api/v1/admin/violations/for-user/" + target.id();
    }

    private List<Integer> strikeNumbers(UUID userId) {
        return new ArrayList<>(
                jdbcTemplate.queryForList(
                        "SELECT strike_number FROM user_strikes WHERE user_id = ?"
                                + " ORDER BY strike_number",
                        Integer.class,
                        userId));
    }

    private int warningCount(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_warnings WHERE user_id = ?", Integer.class, userId);
    }

    private String statusOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM users WHERE id = ?", String.class, userId);
    }

    private OffsetDateTime suspendedUntilOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT suspended_until FROM users WHERE id = ?", OffsetDateTime.class, userId);
    }

    private int auditCount(String actionType, UUID targetUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_actions"
                        + " WHERE action_type = CAST(? AS admin_action_type) AND target_user_id = ?",
                Integer.class,
                actionType,
                targetUserId);
    }

    private TestUser createUser(String prefix, String role) {
        UUID id = UUID.randomUUID();
        String username = prefix + "_" + id.toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                username + "@test.local",
                role);
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private ResponseEntity<Map> post(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> delete(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.DELETE, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> get(String path, TestUser user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> contentOf(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) dataOf(response).get("content");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pageInfoOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) dataOf(response).get("pageInfo");
    }

    @Test
    void unauthenticatedRequest_isRejectedOnEveryDisciplineOperation() {
        UUID any = UUID.randomUUID();

        assertThat(
                        rest.getForEntity("/api/v1/admin/violations/for-user/" + any, Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        "/api/v1/admin/warnings/for-user/" + any,
                                        HttpMethod.POST,
                                        new HttpEntity<>(
                                                Map.of("reasonKey", "spam", "note", "no token")),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        "/api/v1/admin/warnings/" + any,
                                        HttpMethod.DELETE,
                                        new HttpEntity<>(Map.of("reason", "no token")),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void undeclaredQueryParameter_isRejectedOnTheViolationListing() {
        TestUser admin = createUser("viol_bogus_admin", "admin");

        assertThat(
                        get("/api/v1/admin/violations/for-user/" + admin.id() + "?bogus=1", admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
