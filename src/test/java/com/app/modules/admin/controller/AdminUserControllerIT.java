package com.app.modules.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.admin.service.SuspensionExpiryService;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;
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
class AdminUserControllerIT {

    private static final String JWT_SECRET = "admin-user-controller-it-secret-32-chars-min!!!";
    private static final String JWT_ISSUER = "https://admin-user.it.local";

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
        registry.add("JWT_SECRET", () -> JWT_SECRET);
        registry.add("JWT_ISSUER", () -> JWT_ISSUER);
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
        // The sweep is exercised directly through the service; leaving the scheduler off keeps it
        // from racing the tests that assert on a specific suspended_until value.
        registry.add("app.admin.suspension-expiry.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private SuspensionExpiryService suspensionExpiryService;

    private record TestUser(UUID id, String username, String email, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM reports");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void listUsers_administrator_returnsEveryAccountIncludingSoftDeleted() {
        TestUser admin = createUser("list_admin", "admin");
        TestUser moderator = createUser("list_mod", "moderator");
        TestUser ordinary = createUser("list_user", "user");
        softDelete(ordinary.id());

        List<Map<String, Object>> content = contentOf(get("/api/v1/admin/users?limit=100", admin));

        assertThat(content)
                .extracting(row -> row.get("id"))
                .contains(
                        admin.id().toString(), moderator.id().toString(), ordinary.id().toString());
        assertThat(content)
                .filteredOn(row -> ordinary.id().toString().equals(row.get("id")))
                .singleElement()
                .satisfies(row -> assertThat(row.get("deletedAt")).isNotNull());
        // Email is on the administrative shape and on no public one.
        assertThat(content.get(0)).containsKey("email");
    }

    @Test
    void listUsers_moderator_returnsForbidden() {
        TestUser moderator = createUser("forbid_mod", "moderator");

        assertThat(get("/api/v1/admin/users", moderator).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listUsers_ordinaryUser_returnsForbidden() {
        TestUser ordinary = createUser("forbid_user", "user");

        assertThat(get("/api/v1/admin/users", ordinary).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listUsers_statusFilter_returnsOnlyThatStatus() {
        TestUser admin = createUser("filter_admin", "admin");
        TestUser banned = createUser("filter_banned", "user");
        jdbcTemplate.update("UPDATE users SET status = 'banned' WHERE id = ?", banned.id());

        List<Map<String, Object>> content =
                contentOf(get("/api/v1/admin/users?status=banned", admin));

        assertThat(content)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("id")).isEqualTo(banned.id().toString());
                            assertThat(row.get("status")).isEqualTo("banned");
                        });
    }

    @Test
    void listUsers_roleFilter_returnsOnlyThatRole() {
        TestUser admin = createUser("rolefilter_admin", "admin");
        TestUser moderator = createUser("rolefilter_mod", "moderator");
        createUser("rolefilter_user", "user");

        List<Map<String, Object>> content =
                contentOf(get("/api/v1/admin/users?role=moderator", admin));

        assertThat(content)
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("id")).isEqualTo(moderator.id().toString());
                            assertThat(row.get("role")).isEqualTo("moderator");
                        });
        assertThat(admin.id()).isNotEqualTo(moderator.id());
    }

    // Every row shares one created_at, so the page boundary lands inside a tie and only the id
    // tiebreaker keeps it stable. Without the tiebreaker a row is silently dropped or repeated.
    @Test
    void listUsers_pageBoundaryInsideACreatedAtTie_visitsEveryRowExactlyOnce() {
        TestUser admin = createUser("tie_admin", "admin");
        OffsetDateTime shared = OffsetDateTime.parse("2026-03-01T12:00:00Z");
        for (int i = 0; i < 9; i++) {
            TestUser row = createUser("tie_user" + i, "user");
            jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?", shared, row.id());
        }
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?", shared, admin.id());

        List<String> collected = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String path =
                    "/api/v1/admin/users?limit=3" + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<Map> response = get(path, admin);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            contentOf(response).forEach(row -> collected.add((String) row.get("id")));
            Map<?, ?> pageInfo = (Map<?, ?>) dataOf(response).get("pageInfo");
            if (!Boolean.TRUE.equals(pageInfo.get("hasNextPage"))) {
                break;
            }
            cursor = (String) pageInfo.get("endCursor");
        }

        assertThat(collected).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void searchUsers_partialUsernameAndEmailAndExactId_allResolve() {
        TestUser admin = createUser("search_admin", "admin");
        TestUser target = createUser("search_target", "user");

        assertThat(contentOf(get("/api/v1/admin/users/search?q=search_target", admin)))
                .extracting(row -> row.get("id"))
                .containsExactly(target.id().toString());
        assertThat(contentOf(get("/api/v1/admin/users/search?q=" + target.email(), admin)))
                .extracting(row -> row.get("id"))
                .containsExactly(target.id().toString());
        assertThat(contentOf(get("/api/v1/admin/users/search?q=" + target.id(), admin)))
                .extracting(row -> row.get("id"))
                .containsExactly(target.id().toString());
        assertThat(admin.id()).isNotEqualTo(target.id());
    }

    // Same tie hazard as the list endpoint, on the search endpoint's own cursor scope. A cursor
    // issued by one endpoint must also not decode against the other, which the scope tag enforces.
    @Test
    void searchUsers_pageBoundaryInsideACreatedAtTie_visitsEveryRowExactlyOnce() {
        TestUser admin = createUser("searchtie_admin", "admin");
        OffsetDateTime shared = OffsetDateTime.parse("2026-04-01T09:00:00Z");
        for (int i = 0; i < 8; i++) {
            TestUser row = createUser("searchtie_user" + i, "user");
            jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?", shared, row.id());
        }

        List<String> collected = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String path =
                    "/api/v1/admin/users/search?q=searchtie_user&limit=3"
                            + (cursor == null ? "" : "&cursor=" + cursor);
            ResponseEntity<Map> response = get(path, admin);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            contentOf(response).forEach(row -> collected.add((String) row.get("id")));
            Map<?, ?> pageInfo = (Map<?, ?>) dataOf(response).get("pageInfo");
            if (!Boolean.TRUE.equals(pageInfo.get("hasNextPage"))) {
                break;
            }
            cursor = (String) pageInfo.get("endCursor");
        }

        assertThat(collected).hasSize(8).doesNotHaveDuplicates();
        assertThat(admin.id()).isNotNull();
    }

    @Test
    void searchUsers_cursorFromTheListEndpoint_isRejected() {
        TestUser admin = createUser("crossscope_admin", "admin");
        createUser("crossscope_user", "user");
        Map<?, ?> listPageInfo =
                (Map<?, ?>) dataOf(get("/api/v1/admin/users?limit=1", admin)).get("pageInfo");
        String listCursor = (String) listPageInfo.get("endCursor");
        assertThat(listCursor).isNotBlank();

        assertThat(
                        get("/api/v1/admin/users/search?q=crossscope&cursor=" + listCursor, admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void searchUsers_singleCharacterQuery_returnsBadRequest() {
        TestUser admin = createUser("shortq_admin", "admin");

        assertThat(get("/api/v1/admin/users/search?q=a", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // The literal path segment must win over the "/users/{userId}" template, otherwise the search
    // request is answered by the detail handler and fails to bind a UUID.
    @Test
    void searchUsers_searchSegment_isNotCapturedByTheUserIdTemplate() {
        TestUser admin = createUser("route_admin", "admin");

        assertThat(get("/api/v1/admin/users/search?q=route_admin", admin).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void searchUsers_softDeletedAccount_isFoundHereButNotByThePublicSearch() {
        TestUser admin = createUser("deleted_admin", "admin");
        TestUser removed = createUser("deleted_target", "user");
        softDelete(removed.id());

        assertThat(contentOf(get("/api/v1/admin/users/search?q=deleted_target", admin)))
                .extracting(row -> row.get("id"))
                .containsExactly(removed.id().toString());
        assertThat(contentOf(get("/api/v1/users/search?q=deleted_target", admin))).isEmpty();
    }

    @Test
    void getUserDetail_accountWithASession_carriesOriginAndSessions() {
        TestUser admin = createUser("detail_admin", "admin");
        TestUser target = createUser("detail_target", "user");
        jdbcTemplate.update(
                "UPDATE users SET registration_ip = '198.51.100.7', last_login_ip = '203.0.113.9',"
                        + " last_login_at = now() WHERE id = ?",
                target.id());
        UUID sessionId = insertSession(target.id(), OffsetDateTime.now().plusHours(1), null);
        insertSession(target.id(), OffsetDateTime.now().plusHours(1), OffsetDateTime.now());
        insertSession(target.id(), OffsetDateTime.now().minusHours(1), null);

        ResponseEntity<Map> response = get("/api/v1/admin/users/" + target.id(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("registrationIp")).isEqualTo("198.51.100.7");
        assertThat(data.get("lastLoginIp")).isEqualTo("203.0.113.9");
        assertThat(data.get("lastLoginAt")).isNotNull();
        // Only the live session: the revoked one and the expired one are both excluded.
        assertThat((List<Map<String, Object>>) data.get("sessions"))
                .extracting(row -> row.get("id"))
                .containsExactly(sessionId.toString());
        assertThat((List<?>) data.get("reportsAgainst")).isEmpty();
    }

    @Test
    void getUserDetail_reportedAccount_listsTheReportsAgainstIt() {
        TestUser admin = createUser("report_admin", "admin");
        TestUser target = createUser("report_target", "user");
        TestUser reporter = createUser("report_reporter", "user");
        jdbcTemplate.update(
                "INSERT INTO reports (id, reporter_id, report_type, report_reason, entity_id, status)"
                        + " VALUES (?, ?, 'user', 'harassment', ?, 'pending')",
                UUID.randomUUID(),
                reporter.id(),
                target.id());

        Map<String, Object> data = dataOf(get("/api/v1/admin/users/" + target.id(), admin));

        assertThat((List<Map<String, Object>>) data.get("reportsAgainst"))
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.get("reporterId")).isEqualTo(reporter.id().toString());
                            assertThat(row.get("reportReason")).isEqualTo("harassment");
                            assertThat(row.get("status")).isEqualTo("pending");
                        });
    }

    @Test
    void getUserDetail_unknownAccount_returnsNotFound() {
        TestUser admin = createUser("missing_admin", "admin");

        ResponseEntity<Map> response = get("/api/v1/admin/users/" + UUID.randomUUID(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void getUserDetail_softDeletedAccount_resolves() {
        TestUser admin = createUser("softdetail_admin", "admin");
        TestUser removed = createUser("softdetail_target", "user");
        softDelete(removed.id());

        ResponseEntity<Map> response = get("/api/v1/admin/users/" + removed.id(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("deletedAt")).isNotNull();
    }

    @Test
    void suspendUser_withDurationDays_setsTheDeadline() {
        TestUser admin = createUser("suspend_admin", "admin");
        TestUser target = createUser("suspend_target", "user");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/suspend",
                        Map.of("reason", "Fixed term", "durationDays", 7),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(target.id())).isEqualTo("suspended");
        assertThat(suspendedUntilOf(target.id())).isNotNull();
    }

    @Test
    void suspendUser_withoutDurationDays_leavesTheDeadlineNullAndTheSweepIgnoresIt() {
        TestUser admin = createUser("indef_admin", "admin");
        TestUser target = createUser("indef_target", "user");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/suspend",
                                        Map.of("reason", "Indefinite"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(statusOf(target.id())).isEqualTo("suspended");
        assertThat(suspendedUntilOf(target.id())).isNull();

        assertThat(suspensionExpiryService.reinstateExpiredBatch(500)).isZero();
        assertThat(statusOf(target.id())).isEqualTo("suspended");
    }

    @Test
    void unsuspendUser_clearsTheDeadlineSoTheSweepCannotRefire() {
        TestUser admin = createUser("unsuspend_admin", "admin");
        TestUser target = createUser("unsuspend_target", "user");
        patch(
                "/api/v1/admin/users/" + target.id() + "/suspend",
                Map.of("reason", "Fixed term", "durationDays", 7),
                admin);

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/unsuspend",
                                        Map.of("reason", "Appeal upheld"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(statusOf(target.id())).isEqualTo("active");
        assertThat(suspendedUntilOf(target.id())).isNull();
        assertThat(suspensionExpiryService.reinstateExpiredBatch(500)).isZero();
    }

    @Test
    void reinstateExpiredBatch_lapsedSuspension_returnsToActiveWithANullActorAuditRow() {
        TestUser admin = createUser("sweep_admin", "admin");
        TestUser target = createUser("sweep_target", "user");
        patch(
                "/api/v1/admin/users/" + target.id() + "/suspend",
                Map.of("reason", "Fixed term", "durationDays", 1),
                admin);
        jdbcTemplate.update(
                "UPDATE users SET suspended_until = now() - INTERVAL '1 hour' WHERE id = ?",
                target.id());

        assertThat(suspensionExpiryService.reinstateExpiredBatch(500)).isEqualTo(1);

        assertThat(statusOf(target.id())).isEqualTo("active");
        assertThat(suspendedUntilOf(target.id())).isNull();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE target_user_id = ?"
                                        + " AND action_type = 'unsuspend_user' AND admin_id IS NULL",
                                Integer.class,
                                target.id()))
                .isEqualTo(1);
        // The audit row's createdAt is a real timestamp, not the null the write path used to
        // return.
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE created_at IS NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    void reinstateIfExpired_calledTwice_secondCallIsSuccessWithoutASecondAuditRow() {
        TestUser admin = createUser("twice_admin", "admin");
        TestUser target = createUser("twice_target", "user");
        patch(
                "/api/v1/admin/users/" + target.id() + "/suspend",
                Map.of("reason", "Fixed term", "durationDays", 1),
                admin);
        jdbcTemplate.update(
                "UPDATE users SET suspended_until = now() - INTERVAL '1 hour' WHERE id = ?",
                target.id());

        assertThat(suspensionExpiryService.reinstateIfExpired(target.id()).toJson())
                .isEqualTo("active");
        assertThat(suspensionExpiryService.reinstateIfExpired(target.id()).toJson())
                .isEqualTo("active");

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE target_user_id = ?"
                                        + " AND action_type = 'unsuspend_user'",
                                Integer.class,
                                target.id()))
                .isEqualTo(1);
    }

    @Test
    void forceLogout_revokesEverySessionAndReportsTheCount() {
        TestUser admin = createUser("logout_admin", "admin");
        TestUser target = createUser("logout_target", "user");
        insertSession(target.id(), OffsetDateTime.now().plusHours(1), null);
        insertSession(target.id(), OffsetDateTime.now().plusHours(1), null);
        insertSession(target.id(), OffsetDateTime.now().plusHours(1), OffsetDateTime.now());

        ResponseEntity<Map> response =
                post(
                        "/api/v1/admin/users/" + target.id() + "/force-logout",
                        Map.of("reason", "Compromised credentials"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("actionType")).isEqualTo("force_logout");
        assertThat(data.get("createdAt")).isNotNull();
        assertThat(((Map<?, ?>) data.get("metadata")).get("revokedSessions")).isEqualTo(2);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM refresh_tokens"
                                        + " WHERE user_id = ? AND revoked_at IS NULL",
                                Integer.class,
                                target.id()))
                .isZero();
    }

    @Test
    void forceLogout_unknownAccount_returnsNotFound() {
        TestUser admin = createUser("logoutmissing_admin", "admin");

        ResponseEntity<Map> response =
                post(
                        "/api/v1/admin/users/" + UUID.randomUUID() + "/force-logout",
                        Map.of("reason", "Compromised credentials"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void changeRole_demotion_writesTheRoleAndRevokesSessionsInOneTransaction() {
        TestUser admin = createUser("role_admin", "admin");
        TestUser moderator = createUser("role_mod", "moderator");
        insertSession(moderator.id(), OffsetDateTime.now().plusHours(1), null);
        insertSession(moderator.id(), OffsetDateTime.now().plusHours(1), null);

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + moderator.id() + "/role",
                        Map.of("role", "user", "reason", "Stepped down"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(roleOf(moderator.id())).isEqualTo("user");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM refresh_tokens"
                                        + " WHERE user_id = ? AND revoked_at IS NULL",
                                Integer.class,
                                moderator.id()))
                .isZero();
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("actionType")).isEqualTo("change_user_role");
        assertThat(data.get("createdAt")).isNotNull();
        assertThat(((Map<?, ?>) data.get("metadata")).get("previousRole")).isEqualTo("moderator");
        assertThat(((Map<?, ?>) data.get("metadata")).get("newRole")).isEqualTo("user");
    }

    @Test
    void changeRole_promotionUserToModerator_succeeds() {
        TestUser admin = createUser("promote_admin", "admin");
        TestUser ordinary = createUser("promote_user", "user");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + ordinary.id() + "/role",
                                        Map.of("role", "moderator", "reason", "Trained"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(roleOf(ordinary.id())).isEqualTo("moderator");
    }

    @Test
    void changeRole_selfTarget_returnsConflictAndChangesNothing() {
        TestUser admin = createUser("self_admin", "admin");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + admin.id() + "/role",
                        Map.of("role", "moderator", "reason", "Demote myself"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_SELF_ACTION_NOT_ALLOWED");
        assertThat(roleOf(admin.id())).isEqualTo("admin");
        assertThat(auditCount()).isZero();
    }

    @Test
    void changeRole_administratorTarget_returnsForbiddenAndChangesNothing() {
        TestUser admin = createUser("protect_admin", "admin");
        TestUser other = createUser("protect_other", "admin");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + other.id() + "/role",
                        Map.of("role", "moderator", "reason", "Demote peer"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_TARGET_PROTECTED");
        assertThat(roleOf(other.id())).isEqualTo("admin");
        assertThat(auditCount()).isZero();
    }

    @Test
    void protectedAdministratorTarget_answersTheSameStatusOnBanAndOnRoleChange() {
        // One cause, one answer. A client that renders a Ban control and a Change role control
        // has to branch on the same condition for both, and two statuses for the same reason is
        // what made a client-side copy of the rule the only way to get it right.
        TestUser admin = createUser("onestatus_admin", "admin");
        TestUser other = createUser("onestatus_other", "admin");

        ResponseEntity<Map> ban =
                patch(
                        "/api/v1/admin/users/" + other.id() + "/ban",
                        Map.of("reason", "Ban peer"),
                        admin);
        ResponseEntity<Map> role =
                patch(
                        "/api/v1/admin/users/" + other.id() + "/role",
                        Map.of("role", "moderator", "reason", "Demote peer"),
                        admin);

        assertThat(ban.getStatusCode()).isEqualTo(role.getStatusCode());
        assertThat(ban.getBody().get("code")).isEqualTo(role.getBody().get("code"));
        assertThat(ban.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ban.getBody().get("code")).isEqualTo("ADMIN_TARGET_PROTECTED");
    }

    @Test
    void changeRole_skipLevelPromotion_returnsConflictAndChangesNothing() {
        TestUser admin = createUser("skip_admin", "admin");
        TestUser ordinary = createUser("skip_user", "user");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + ordinary.id() + "/role",
                        Map.of("role", "admin", "reason", "Straight to admin"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_ROLE_TRANSITION_NOT_ALLOWED");
        assertThat(roleOf(ordinary.id())).isEqualTo("user");
        assertThat(auditCount()).isZero();
    }

    @Test
    void changeRole_roleAlreadyHeld_returnsConflict() {
        TestUser admin = createUser("noop_admin", "admin");
        TestUser moderator = createUser("noop_mod", "moderator");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + moderator.id() + "/role",
                        Map.of("role", "moderator", "reason", "No change"),
                        admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_ROLE_TRANSITION_NOT_ALLOWED");
    }

    @Test
    void changeRole_unknownRole_returnsBadRequest() {
        TestUser admin = createUser("badrole_admin", "admin");
        TestUser ordinary = createUser("badrole_user", "user");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + ordinary.id() + "/role",
                                        Map.of("role", "superuser", "reason", "Invented role"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changeRole_moderatorActor_returnsForbidden() {
        TestUser moderator = createUser("modactor_mod", "moderator");
        TestUser ordinary = createUser("modactor_user", "user");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + ordinary.id() + "/role",
                                        Map.of(
                                                "role",
                                                "moderator",
                                                "reason",
                                                "Self promotion ring"),
                                        moderator)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(roleOf(ordinary.id())).isEqualTo("user");
    }

    @Test
    void changeRole_unknownAccount_returnsNotFound() {
        TestUser admin = createUser("rolemissing_admin", "admin");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + UUID.randomUUID() + "/role",
                                        Map.of("role", "moderator", "reason", "Ghost"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void forceLogout_targetAccessToken_isRejectedOnTheVeryNextRequest() {
        TestUser admin = createUser("epoch_logout_admin", "admin");
        TestUser target = createUser("epoch_logout_user", "user");
        assertThat(get("/api/v1/users/me", target).getStatusCode()).isEqualTo(HttpStatus.OK);

        post(
                "/api/v1/admin/users/" + target.id() + "/force-logout",
                Map.of("reason", "Compromised credentials"),
                admin);

        assertThat(get("/api/v1/users/me", target).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tokenEpochOf(target.id())).isEqualTo(1);
    }

    @Test
    void changeRole_targetAccessToken_isRejectedOnTheVeryNextRequest() {
        TestUser admin = createUser("epoch_role_admin", "admin");
        TestUser moderator = createUser("epoch_role_mod", "moderator");
        assertThat(get("/api/v1/users/me", moderator).getStatusCode()).isEqualTo(HttpStatus.OK);

        patch(
                "/api/v1/admin/users/" + moderator.id() + "/role",
                Map.of("role", "user", "reason", "Stepped down"),
                admin);

        assertThat(get("/api/v1/users/me", moderator).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(tokenEpochOf(moderator.id())).isEqualTo(1);
    }

    @Test
    void forceLogout_tokenMintedAtTheNewEpoch_isAccepted() {
        TestUser admin = createUser("epoch_reissue_admin", "admin");
        TestUser target = createUser("epoch_reissue_user", "user");
        post(
                "/api/v1/admin/users/" + target.id() + "/force-logout",
                Map.of("reason", "Compromised credentials"),
                admin);

        TestUser reissued =
                new TestUser(
                        target.id(),
                        target.username(),
                        target.email(),
                        jwtTokenProvider.generateAccessToken(
                                target.id(), "USER", tokenEpochOf(target.id())));

        assertThat(get("/api/v1/users/me", reissued).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resolve_tokenWithoutEpochClaim_isAcceptedAgainstAnUntouchedAccount() {
        TestUser target = createUser("epoch_legacy_user", "user");

        TestUser legacy =
                new TestUser(
                        target.id(),
                        target.username(),
                        target.email(),
                        mintTokenWithoutEpochClaim(target.id()));

        assertThat(get("/api/v1/users/me", legacy).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resolve_tokenWithoutEpochClaim_isRejectedAfterForceLogout() {
        TestUser admin = createUser("epoch_legacy_admin", "admin");
        TestUser target = createUser("epoch_legacy_gone", "user");
        TestUser legacy =
                new TestUser(
                        target.id(),
                        target.username(),
                        target.email(),
                        mintTokenWithoutEpochClaim(target.id()));

        post(
                "/api/v1/admin/users/" + target.id() + "/force-logout",
                Map.of("reason", "Compromised credentials"),
                admin);

        assertThat(get("/api/v1/users/me", legacy).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private int tokenEpochOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT token_epoch FROM users WHERE id = ?", Integer.class, userId);
    }

    // Reproduces a token minted before the epoch claim existed. The encoder and the claim set are
    // the ones JwtTokenProvider uses, minus the epoch claim, so this differs from a live token in
    // exactly the one respect the deploy-time compatibility rule is about.
    private String mintTokenWithoutEpochClaim(UUID userId) {
        SecretKey key =
                new SecretKeySpec(
                        JWT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(JWT_ISSUER)
                        .audience(List.of("App"))
                        .subject(userId.toString())
                        .claim("role", "USER")
                        .claim("jti", UUID.randomUUID().toString())
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(now.plusSeconds(900))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private TestUser createUser(String prefix, String role) {
        UUID id = UUID.randomUUID();
        String username = prefix + "_" + id.toString().substring(0, 8);
        String email = username + "@test.local";
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                email,
                role);
        return new TestUser(
                id,
                username,
                email,
                jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private void softDelete(UUID userId) {
        jdbcTemplate.update("UPDATE users SET deleted_at = now() WHERE id = ?", userId);
    }

    private UUID insertSession(UUID userId, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens"
                        + " (id, user_id, token_hash, device_id, user_agent, ip_address, expires_at,"
                        + " revoked_at)"
                        + " VALUES (?, ?, ?, 'device-1', 'JUnit', '198.51.100.5', ?, ?)",
                id,
                userId,
                id.toString(),
                expiresAt,
                revokedAt);
        return id;
    }

    private String statusOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM users WHERE id = ?", String.class, userId);
    }

    private String roleOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT role::text FROM users WHERE id = ?", String.class, userId);
    }

    private OffsetDateTime suspendedUntilOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT suspended_until FROM users WHERE id = ?", OffsetDateTime.class, userId);
    }

    private int auditCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_actions", Integer.class);
    }

    private ResponseEntity<Map> get(String path, TestUser user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> patch(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> post(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.POST, new HttpEntity<>(body, authHeaders(user)), Map.class);
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

    @Test
    void unauthenticatedRequest_isRejectedOnEveryAccountOperation() {
        UUID any = UUID.randomUUID();

        assertThat(rest.getForEntity("/api/v1/admin/users", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/admin/users/search?q=abc", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/admin/users/" + any, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        "/api/v1/admin/users/" + any + "/ban",
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(Map.of("reason", "no token")),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void undeclaredQueryParameter_isRejectedOnEveryAccountListing() {
        TestUser admin = createUser("users_bogus_admin", "admin");

        assertThat(get("/api/v1/admin/users?bogus=1", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/admin/users/search?q=abc&bogus=1", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/admin/users/" + admin.id() + "?bogus=1", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
