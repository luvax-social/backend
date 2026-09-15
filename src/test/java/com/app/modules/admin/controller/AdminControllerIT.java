package com.app.modules.admin.controller;

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
class AdminControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        // These tests drive login, register and report submission as setup, not as the
        // subject under test. The kill switch keeps them off the network: the dev profile
        // defaults the secret to Cloudflare's test key, and a real siteverify call would
        // make the suite depend on an external service being reachable.
        registry.add("TURNSTILE_AUTH_ENABLED", () -> false);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "admin-controller-it-secret-32-chars-minimum!!!");
        registry.add("JWT_ISSUER", () -> "https://admin.it.local");
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
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS trg_fail_admin_action_insert ON admin_actions");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_admin_action_insert()");
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM reports");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM comments");
        jdbcTemplate.update("DELETE FROM post_hashtags");
        jdbcTemplate.update("DELETE FROM hashtags");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void banUser_clientSuppliedMetadata_isRejected() {
        TestUser actor = createUser("meta_bound_admin", "admin");
        TestUser target = createUser("meta_bound_target", "user");

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", "Severe abuse");
        body.put("metadata", Map.of("severity", "high"));

        ResponseEntity<Map> response =
                patch("/api/v1/admin/users/" + target.id() + "/ban", body, actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("MALFORMED_REQUEST_BODY");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE target_user_id = ?",
                                Integer.class,
                                target.id()))
                .isZero();
    }

    @Test
    void banUser_writeResponse_carriesCreatedAt() {
        TestUser actor = createUser("created_at_admin", "admin");
        TestUser target = createUser("created_at_target", "user");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/ban",
                        Map.of("reason", "Audit timestamp must reach the client"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("createdAt")).isNotNull();
    }

    @Test
    void banUser_regularUser_returnsForbidden() {
        TestUser actor = createUser("forbidden_actor", "user");
        TestUser target = createUser("forbidden_target", "user");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/ban",
                        Map.of("reason", "Violation"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void userStatusOperations_moderator_returnForbiddenAndLeaveNoTrace() {
        TestUser actor = createUser("status_moderator", "moderator");
        TestUser target = createUser("status_target", "user");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/suspend",
                                        Map.of("reason", "Repeated harassment"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/unsuspend",
                                        Map.of("reason", "Suspension completed"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/ban",
                                        Map.of("reason", "Severe abuse"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/unban",
                                        Map.of("reason", "Appeal accepted"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM users WHERE id = ?",
                                String.class,
                                target.id()))
                .isEqualTo("active");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE admin_id = ?",
                                Integer.class,
                                actor.id()))
                .isZero();
    }

    @Test
    void userStatusOperations_administrator_applyAllFourContracts() {
        TestUser actor = createUser("status_admin", "admin");
        TestUser target = createUser("status_target", "user");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/suspend",
                                        Map.of("reason", "Repeated harassment"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/unsuspend",
                                        Map.of("reason", "Suspension completed"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/ban",
                                        Map.of("reason", "Severe abuse"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/unban",
                                        Map.of("reason", "Appeal accepted"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM users WHERE id = ?",
                                String.class,
                                target.id()))
                .isEqualTo("active");
        assertThat(auditCount("suspend_user", target.id())).isEqualTo(1);
        assertThat(auditCount("unsuspend_user", target.id())).isEqualTo(1);
        assertThat(auditCount("ban_user", target.id())).isEqualTo(1);
        assertThat(auditCount("unban_user", target.id())).isEqualTo(1);
    }

    @Test
    void removePost_moderator_staysReachableOnTheSharedPrefix() {
        TestUser actor = createUser("shared_prefix_mod", "moderator");
        TestUser owner = createUser("shared_prefix_owner", "user");
        UUID postId = insertPost(owner.id());

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/posts/" + postId + "/remove",
                        Map.of("reason", "Policy violation"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM posts WHERE id = ?",
                                String.class,
                                postId))
                .isEqualTo("removed");
    }

    @Test
    void suspendUser_administratorTargetingItself_returnsConflict() {
        TestUser actor = createUser("self_action_admin", "admin");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + actor.id() + "/suspend",
                        Map.of("reason", "Self action"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_SELF_ACTION_NOT_ALLOWED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM users WHERE id = ?",
                                String.class,
                                actor.id()))
                .isEqualTo("active");
    }

    @Test
    void banUser_administratorTargetingAnotherAdministrator_returnsForbidden() {
        TestUser actor = createUser("protected_actor", "admin");
        TestUser target = createUser("protected_admin", "admin");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/ban",
                        Map.of("reason", "Admin on admin"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_TARGET_PROTECTED");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM users WHERE id = ?",
                                String.class,
                                target.id()))
                .isEqualTo("active");
    }

    // The path matcher rejects this before the self-action guard is reached, so the body carries
    // the
    // generic FORBIDDEN code rather than ADMIN_SELF_ACTION_NOT_ALLOWED.
    @Test
    void suspendUser_moderatorTargetingItself_returnsForbidden() {
        TestUser actor = createUser("self_action_moderator", "moderator");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + actor.id() + "/suspend",
                        Map.of("reason", "Self action"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("FORBIDDEN");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM users WHERE id = ?",
                                String.class,
                                actor.id()))
                .isEqualTo("active");
    }

    @Test
    void actionsForUser_oldPathUnderTheAdminOnlyPrefix_isNotReachableByAModerator() {
        TestUser actor = createUser("legacy_path_moderator", "moderator");
        TestUser target = createUser("legacy_path_target", "user");

        ResponseEntity<Map> response =
                get("/api/v1/admin/users/" + target.id() + "/actions", actor);

        assertThat(response.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
    }

    @Test
    void moderatePost_removeThenRestore_updatesPostAndCreatesAuditTrail() {
        TestUser actor = createUser("post_admin", "admin");
        TestUser owner = createUser("post_owner", "user");
        UUID postId = insertPost(owner.id());

        ResponseEntity<Map> removed =
                patch(
                        "/api/v1/admin/posts/" + postId + "/remove",
                        Map.of("reason", "Policy violation"),
                        actor);
        ResponseEntity<Map> restored =
                patch(
                        "/api/v1/admin/posts/" + postId + "/restore",
                        Map.of("reason", "Appeal accepted"),
                        actor);

        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> state =
                jdbcTemplate.queryForMap(
                        "SELECT status::text AS status, deleted_at FROM posts WHERE id = ?",
                        postId);
        assertThat(state.get("status")).isEqualTo("published");
        assertThat(state.get("deleted_at")).isNull();
        assertThat(auditCount("remove_post", owner.id())).isEqualTo(1);
        assertThat(auditCount("restore_post", owner.id())).isEqualTo(1);
    }

    @Test
    void moderateComment_removeThenRestore_updatesCommentAndCreatesAuditTrail() {
        TestUser actor = createUser("comment_moderator", "moderator");
        TestUser owner = createUser("comment_owner", "user");
        UUID postId = insertPost(actor.id());
        UUID commentId = insertComment(postId, owner.id());

        assertThat(
                        patch(
                                        "/api/v1/admin/comments/" + commentId + "/remove",
                                        Map.of("reason", "Harassment"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        patch(
                                        "/api/v1/admin/comments/" + commentId + "/restore",
                                        Map.of("reason", "Appeal accepted"),
                                        actor)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT deleted_at IS NULL FROM comments WHERE id = ?",
                                Boolean.class,
                                commentId))
                .isTrue();
        assertThat(auditCount("remove_comment", owner.id())).isEqualTo(1);
        assertThat(auditCount("restore_comment", owner.id())).isEqualTo(1);
    }

    @Test
    void reportOperations_admin_resolveAndDismissReports() {
        TestUser actor = createUser("report_admin", "admin");
        TestUser reporter = createUser("report_reporter", "user");
        TestUser target = createUser("report_target", "user");
        TestUser secondTarget = createUser("report_target_2", "user");
        UUID resolvedReportId = insertReport(reporter.id(), target.id());
        UUID dismissedReportId = insertReport(reporter.id(), secondTarget.id());

        ResponseEntity<Map> resolved =
                patch(
                        "/api/v1/admin/reports/" + resolvedReportId + "/resolve",
                        Map.of("reason", "Violation confirmed"),
                        actor);
        ResponseEntity<Map> dismissed =
                patch(
                        "/api/v1/admin/reports/" + dismissedReportId + "/dismiss",
                        Map.of("reason", "No violation found"),
                        actor);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dismissed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> report =
                jdbcTemplate.queryForMap(
                        "SELECT status::text AS status, reviewed_by, resolution_note "
                                + "FROM reports WHERE id = ?",
                        dismissedReportId);
        assertThat(report.get("status")).isEqualTo("dismissed");
        assertThat(report.get("reviewed_by")).isEqualTo(actor.id());
        assertThat(report.get("resolution_note")).isEqualTo("No violation found");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE report_id = ?",
                                Integer.class,
                                dismissedReportId))
                .isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM reports WHERE id = ?",
                                String.class,
                                resolvedReportId))
                .isEqualTo("resolved");
    }

    @Test
    void listAndGetActions_author_returnsPersistedAuditEvent() {
        // Author and reader are the same account on purpose. The three read endpoints are scoped to
        // the reading moderator's own rows, so a reader that did not author the row is asserted on
        // separately by the scoping tests below rather than here.
        TestUser actor = createUser("query_admin", "admin");
        TestUser target = createUser("query_target", "user");
        ResponseEntity<Map> mutation =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/ban",
                        Map.of("reason", "Severe abuse"),
                        actor);
        UUID actionId = UUID.fromString((String) dataOf(mutation).get("id"));

        ResponseEntity<Map> list = get("/api/v1/admin/actions?actionType=ban_user", actor);
        ResponseEntity<Map> detail = get("/api/v1/admin/actions/" + actionId, actor);
        ResponseEntity<Map> forUser = get("/api/v1/admin/actions/for-user/" + target.id(), actor);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(list)).hasSize(1);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(detail).get("id")).isEqualTo(actionId.toString());
        assertThat(forUser.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(forUser)).hasSize(1);
    }

    @Test
    void getActions_moderator_seesOnlyRowsItAuthored() {
        TestUser admin = createUser("scope_admin", "admin");
        TestUser moderator = createUser("scope_mod", "moderator");
        TestUser target = createUser("scope_target", "user");
        UUID postId = insertPost(target.id());

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/ban",
                                        Map.of("reason", "Administrator authored row"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        patch(
                                        "/api/v1/admin/posts/" + postId + "/remove",
                                        Map.of("reason", "Moderator authored row"),
                                        moderator)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Map<?, ?>> moderatorView = contentOf(get("/api/v1/admin/actions", moderator));
        assertThat(moderatorView).hasSize(1);
        assertThat(moderatorView.get(0).get("adminId")).isEqualTo(moderator.id().toString());

        assertThat(contentOf(get("/api/v1/admin/actions", admin))).hasSize(2);
    }

    @Test
    void getActions_moderatorFilteringByAnotherActor_seesNothing() {
        TestUser admin = createUser("filter_admin", "admin");
        TestUser moderator = createUser("filter_mod", "moderator");
        TestUser target = createUser("filter_target", "user");

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/ban",
                                        Map.of("reason", "Administrator authored row"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(contentOf(get("/api/v1/admin/actions?adminId=" + admin.id(), moderator)))
                .isEmpty();
    }

    @Test
    void getActionById_moderatorReadingAnotherActorsRow_returnsNotFound() {
        TestUser admin = createUser("byid_admin", "admin");
        TestUser moderator = createUser("byid_mod", "moderator");
        TestUser target = createUser("byid_target", "user");

        ResponseEntity<Map> ban =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/ban",
                        Map.of("reason", "Administrator authored row"),
                        admin);
        assertThat(ban.getStatusCode()).isEqualTo(HttpStatus.OK);
        String actionId = (String) dataOf(ban).get("id");

        assertThat(get("/api/v1/admin/actions/" + actionId, moderator).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/api/v1/admin/actions/" + actionId, admin).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void getActionsForUser_moderator_seesOnlyRowsItAuthored() {
        TestUser admin = createUser("foruser_admin", "admin");
        TestUser moderator = createUser("foruser_mod", "moderator");
        TestUser target = createUser("foruser_target", "user");
        UUID postId = insertPost(target.id());

        assertThat(
                        patch(
                                        "/api/v1/admin/users/" + target.id() + "/ban",
                                        Map.of("reason", "Administrator authored row"),
                                        admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        patch(
                                        "/api/v1/admin/posts/" + postId + "/remove",
                                        Map.of("reason", "Moderator authored row"),
                                        moderator)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Map<?, ?>> moderatorView =
                contentOf(get("/api/v1/admin/actions/for-user/" + target.id(), moderator));
        assertThat(moderatorView).hasSize(1);
        assertThat(moderatorView.get(0).get("adminId")).isEqualTo(moderator.id().toString());

        assertThat(contentOf(get("/api/v1/admin/actions/for-user/" + target.id(), admin)))
                .hasSize(2);
    }

    @Test
    void listActions_sizeAboveMax_returnsBadRequest() {
        TestUser actor = createUser("size_moderator", "moderator");

        ResponseEntity<Map> response = get("/api/v1/admin/actions?limit=999", actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteActor_existingAuditEvent_preservesAuditWithNullActor() {
        TestUser actor = createUser("cascade_admin", "admin");
        TestUser target = createUser("cascade_target", "user");
        ResponseEntity<Map> mutation =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/ban",
                        Map.of("reason", "Permanent violation"),
                        actor);
        UUID actionId = UUID.fromString((String) dataOf(mutation).get("id"));

        jdbcTemplate.update("DELETE FROM users WHERE id = ?", actor.id());

        Map<String, Object> audit =
                jdbcTemplate.queryForMap(
                        "SELECT id, admin_id FROM admin_actions WHERE id = ?", actionId);
        assertThat(audit.get("id")).isEqualTo(actionId);
        assertThat(audit.get("admin_id")).isNull();
    }

    @Test
    void banUser_auditInsertFails_rollsBackUserStatus() {
        TestUser actor = createUser("rollback_admin", "admin");
        TestUser target = createUser("rollback_target", "user");
        jdbcTemplate.execute(
                "CREATE FUNCTION fail_admin_action_insert() RETURNS trigger AS $$ "
                        + "BEGIN RAISE EXCEPTION 'forced audit failure'; END; $$ LANGUAGE plpgsql");
        jdbcTemplate.execute(
                "CREATE TRIGGER trg_fail_admin_action_insert BEFORE INSERT ON admin_actions "
                        + "FOR EACH ROW EXECUTE FUNCTION fail_admin_action_insert()");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/users/" + target.id() + "/ban",
                        Map.of("reason", "Rollback verification"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM users WHERE id = ?",
                                String.class,
                                target.id()))
                .isEqualTo("active");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM admin_actions WHERE target_user_id = ?",
                                Integer.class,
                                target.id()))
                .isZero();
    }

    @Test
    void removePost_moderatedPost_detachesHashtagsAndEnqueuesIndexDelete() {
        TestUser actor = createUser("hashtag_detach_mod", "moderator");
        TestUser owner = createUser("hashtag_detach_owner", "user");
        UUID postId = insertPost(owner.id());
        attachHashtag(postId, "moderationparity");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/posts/" + postId + "/remove",
                        Map.of("reason", "Removal must match the owner path"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM post_hashtags WHERE post_id = ?",
                                Integer.class,
                                postId))
                .isZero();
        assertThat(outboxCount("post.index.delete.v1", postId)).isOne();
    }

    @Test
    void restorePost_moderatedPost_reDerivesHashtagsAndEnqueuesIndexUpsert() {
        TestUser actor = createUser("hashtag_rederive_mod", "moderator");
        TestUser owner = createUser("hashtag_reder_owner", "user");
        UUID postId = insertPostWithCaption(owner.id(), "back online #moderationparity");
        attachHashtag(postId, "moderationparity");
        patch(
                "/api/v1/admin/posts/" + postId + "/remove",
                Map.of("reason", "Staged for restore"),
                actor);

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/posts/" + postId + "/restore",
                        Map.of("reason", "Restore must match the owner path"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM post_hashtags WHERE post_id = ?",
                                Integer.class,
                                postId))
                .isOne();
        assertThat(outboxCount("post.index.upsert.v1", postId)).isOne();
    }

    @Test
    void restorePost_removedFromDraft_returnsPostToDraft() {
        TestUser actor = createUser("draft_restore_mod", "moderator");
        TestUser owner = createUser("draft_restore_owner", "user");
        UUID postId = insertDraftPost(owner.id());
        patch(
                "/api/v1/admin/posts/" + postId + "/remove",
                Map.of("reason", "Draft removed by moderation"),
                actor);

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/posts/" + postId + "/restore",
                        Map.of("reason", "Restore must not publish a draft"),
                        actor);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM posts WHERE id = ?",
                                String.class,
                                postId))
                .isEqualTo("draft");
    }

    @Test
    void escalateReport_pendingReport_recordsTheEscalationAndTheAuditRow() {
        TestUser moderator = createUser("esc_mod", "moderator");
        TestUser reporter = createUser("esc_reporter", "user");
        TestUser target = createUser("esc_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/reports/" + reportId + "/escalate",
                        Map.of("reason", "Reported account is a moderator; outside my remit"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("actionType")).isEqualTo("escalate_report");
        Map<String, Object> report =
                jdbcTemplate.queryForMap(
                        "SELECT status::text AS status, escalated_by, escalated_at,"
                                + " escalation_reason FROM reports WHERE id = ?",
                        reportId);
        assertThat(report.get("status")).isEqualTo("escalated");
        assertThat(report.get("escalated_by")).isEqualTo(moderator.id());
        assertThat(report.get("escalated_at")).isNotNull();
        assertThat(report.get("escalation_reason"))
                .isEqualTo("Reported account is a moderator; outside my remit");
    }

    @Test
    void escalateReport_removesItFromTheModeratorQueueButNotFromTheDirectRead() {
        TestUser moderator = createUser("escq_mod", "moderator");
        TestUser reporter = createUser("escq_reporter", "user");
        TestUser target = createUser("escq_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());
        assertThat(reportIdsIn(get("/api/v1/reports", moderator))).contains(reportId.toString());

        patch(
                "/api/v1/admin/reports/" + reportId + "/escalate",
                Map.of("reason", "Handing this up"),
                moderator);

        assertThat(reportIdsIn(get("/api/v1/reports", moderator)))
                .doesNotContain(reportId.toString());
        ResponseEntity<Map> direct = get("/api/v1/reports/" + reportId, moderator);
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(direct).get("status")).isEqualTo("escalated");
    }

    @Test
    void escalateReport_administratorQueue_stillContainsIt() {
        TestUser admin = createUser("escadm", "admin");
        TestUser moderator = createUser("escadm_mod", "moderator");
        TestUser reporter = createUser("escadm_rep", "user");
        TestUser target = createUser("escadm_tgt", "user");
        UUID reportId = insertReport(reporter.id(), target.id());
        patch(
                "/api/v1/admin/reports/" + reportId + "/escalate",
                Map.of("reason", "Handing this up"),
                moderator);

        assertThat(reportIdsIn(get("/api/v1/reports", admin))).contains(reportId.toString());
        assertThat(reportIdsIn(get("/api/v1/reports?status=escalated", admin)))
                .contains(reportId.toString());
    }

    @Test
    void escalateReport_terminalReport_returnsConflict() {
        TestUser admin = createUser("esct_admin", "admin");
        TestUser moderator = createUser("esct_mod", "moderator");
        TestUser reporter = createUser("esct_reporter", "user");
        TestUser target = createUser("esct_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());
        patch(
                "/api/v1/admin/reports/" + reportId + "/resolve",
                Map.of("reason", "Confirmed and handled"),
                admin);

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/reports/" + reportId + "/escalate",
                        Map.of("reason", "Too late"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_INVALID_TRANSITION");
    }

    @Test
    void escalateReport_alreadyEscalated_returnsConflict() {
        TestUser moderator = createUser("esce_mod", "moderator");
        TestUser reporter = createUser("esce_reporter", "user");
        TestUser target = createUser("esce_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());
        patch(
                "/api/v1/admin/reports/" + reportId + "/escalate",
                Map.of("reason", "First"),
                moderator);

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/reports/" + reportId + "/escalate",
                        Map.of("reason", "Again"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resolveReport_escalatedReport_refusesAModeratorAndAcceptsAnAdministrator() {
        TestUser admin = createUser("escr_admin", "admin");
        TestUser moderator = createUser("escr_mod", "moderator");
        TestUser reporter = createUser("escr_reporter", "user");
        TestUser target = createUser("escr_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());
        patch(
                "/api/v1/admin/reports/" + reportId + "/escalate",
                Map.of("reason", "Handing this up"),
                moderator);

        ResponseEntity<Map> refused =
                patch(
                        "/api/v1/admin/reports/" + reportId + "/resolve",
                        Map.of("reason", "Not mine to close"),
                        moderator);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOfReport(reportId)).isEqualTo("escalated");

        ResponseEntity<Map> accepted =
                patch(
                        "/api/v1/admin/reports/" + reportId + "/resolve",
                        Map.of("reason", "Reviewed and closed"),
                        admin);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOfReport(reportId)).isEqualTo("resolved");
    }

    @Test
    void dismissReport_escalatedReport_refusesAModerator() {
        TestUser moderator = createUser("escd_mod", "moderator");
        TestUser reporter = createUser("escd_reporter", "user");
        TestUser target = createUser("escd_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());
        patch(
                "/api/v1/admin/reports/" + reportId + "/escalate",
                Map.of("reason", "Handing this up"),
                moderator);

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/reports/" + reportId + "/dismiss",
                        Map.of("reason", "Not actionable"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusOfReport(reportId)).isEqualTo("escalated");
    }

    @Test
    void countEscalatedReports_movesWithEscalationAndClosure() {
        TestUser admin = createUser("cnt_admin", "admin");
        TestUser moderator = createUser("cnt_mod", "moderator");
        TestUser reporter = createUser("cnt_reporter", "user");
        TestUser target = createUser("cnt_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());

        assertThat(escalatedCount(admin)).isZero();

        patch(
                "/api/v1/admin/reports/" + reportId + "/escalate",
                Map.of("reason", "Handing this up"),
                moderator);
        assertThat(escalatedCount(admin)).isEqualTo(1);

        patch(
                "/api/v1/admin/reports/" + reportId + "/resolve",
                Map.of("reason", "Reviewed and closed"),
                admin);
        assertThat(escalatedCount(admin)).isZero();
    }

    @Test
    void countEscalatedReports_moderatorActor_returnsForbidden() {
        TestUser moderator = createUser("cntf_mod", "moderator");

        assertThat(get("/api/v1/admin/reports/escalated/count", moderator).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listReports_moderatorReplayingAnAdministratorCursor_isRejected() {
        TestUser admin = createUser("rc_admin", "admin");
        TestUser moderator = createUser("rc_mod", "moderator");
        TestUser reporter = createUser("rc_reporter", "user");
        insertReport(reporter.id(), createUser("rc_t1", "user").id());
        insertReport(reporter.id(), createUser("rc_t2", "user").id());
        ResponseEntity<Map> adminPage = get("/api/v1/reports?limit=1", admin);
        String adminCursor = (String) pageInfoOf(adminPage).get("endCursor");
        assertThat(adminCursor).isNotBlank();

        ResponseEntity<Map> replayed =
                get("/api/v1/reports?limit=1&cursor=" + adminCursor, moderator);

        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(replayed.getBody().get("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void listReports_moderatorAskingForAResolvedStatus_returnsAnEmptyPage() {
        TestUser admin = createUser("mq_admin", "admin");
        TestUser moderator = createUser("mq_mod", "moderator");
        TestUser reporter = createUser("mq_reporter", "user");
        TestUser target = createUser("mq_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());
        patch(
                "/api/v1/admin/reports/" + reportId + "/resolve",
                Map.of("reason", "Confirmed and handled"),
                admin);

        assertThat(reportIdsIn(get("/api/v1/reports?status=resolved", moderator))).isEmpty();
        assertThat(reportIdsIn(get("/api/v1/reports?status=resolved", admin)))
                .contains(reportId.toString());
        assertThat(reportIdsIn(get("/api/v1/reports", moderator))).isEmpty();
    }

    private long escalatedCount(TestUser actor) {
        ResponseEntity<Map> response = get("/api/v1/admin/reports/escalated/count", actor);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) dataOf(response).get("count")).longValue();
    }

    private String statusOfReport(UUID reportId) {
        return jdbcTemplate.queryForObject(
                "SELECT status::text FROM reports WHERE id = ?", String.class, reportId);
    }

    private static List<String> reportIdsIn(ResponseEntity<Map> response) {
        return contentOf(response).stream().map(row -> (String) row.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pageInfoOf(ResponseEntity<Map> response) {
        return (Map<String, Object>) dataOf(response).get("pageInfo");
    }

    @Test
    void getReportTarget_postHiddenByABlock_isVisibleToTheModeratorAndStaysHiddenPublicly() {
        TestUser moderator = createUser("tgt_mod", "moderator");
        TestUser author = createUser("tgt_author", "user");
        TestUser reporter = createUser("tgt_reporter", "user");
        UUID postId = insertPost(author.id());
        blockUser(author.id(), moderator.id());
        UUID reportId = insertPostReport(reporter.id(), postId);

        ResponseEntity<Map> viaReport =
                get("/api/v1/admin/reports/" + reportId + "/target", moderator);

        assertThat(viaReport.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> target = dataOf(viaReport);
        assertThat(target.get("reportType")).isEqualTo("post");
        assertThat(target.get("entityId")).isEqualTo(postId.toString());
        assertThat(target.get("ownerId")).isEqualTo(author.id().toString());
        assertThat(target.get("text")).isEqualTo("Moderation target");
        assertThat(target.get("removed")).isEqualTo(false);
        assertThat(viaReport.getHeaders().getCacheControl()).contains("no-store");

        // The second half matters as much as the first: the bypass must not have leaked into the
        // ordinary read path.
        ResponseEntity<Map> viaPublicEndpoint = get("/api/v1/posts/" + postId, moderator);
        assertThat(viaPublicEndpoint.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viaPublicEndpoint.getBody().get("code")).isEqualTo("POST_FORBIDDEN");
    }

    @Test
    void getReportTarget_writesNoAuditRow() {
        TestUser moderator = createUser("tgta_mod", "moderator");
        TestUser author = createUser("tgta_author", "user");
        TestUser reporter = createUser("tgta_reporter", "user");
        UUID postId = insertPost(author.id());
        UUID reportId = insertPostReport(reporter.id(), postId);

        get("/api/v1/admin/reports/" + reportId + "/target", moderator);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM admin_actions", Integer.class))
                .isZero();
    }

    @Test
    void getReportTarget_deletedEntity_returnsGone() {
        TestUser moderator = createUser("tgtg_mod", "moderator");
        TestUser author = createUser("tgtg_author", "user");
        TestUser reporter = createUser("tgtg_reporter", "user");
        UUID postId = insertPost(author.id());
        UUID reportId = insertPostReport(reporter.id(), postId);
        jdbcTemplate.update("DELETE FROM posts WHERE id = ?", postId);

        ResponseEntity<Map> response =
                get("/api/v1/admin/reports/" + reportId + "/target", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_TARGET_GONE");
    }

    @Test
    void getReportTarget_softDeletedPost_isStillReviewableAndFlaggedRemoved() {
        TestUser moderator = createUser("tgts_mod", "moderator");
        TestUser author = createUser("tgts_author", "user");
        TestUser reporter = createUser("tgts_reporter", "user");
        UUID postId = insertPost(author.id());
        UUID reportId = insertPostReport(reporter.id(), postId);
        jdbcTemplate.update(
                "UPDATE posts SET status = 'removed', deleted_at = NOW() WHERE id = ?", postId);

        ResponseEntity<Map> response =
                get("/api/v1/admin/reports/" + reportId + "/target", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("removed")).isEqualTo(true);
        assertThat(dataOf(response).get("status")).isEqualTo("removed");
    }

    @Test
    void getReportTarget_unknownReport_returnsNotFound() {
        TestUser moderator = createUser("tgtn_mod", "moderator");

        ResponseEntity<Map> response =
                get("/api/v1/admin/reports/" + UUID.randomUUID() + "/target", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_NOT_FOUND");
    }

    @Test
    void getReportTarget_reportedAccount_rendersTheProfile() {
        TestUser moderator = createUser("tgtu_mod", "moderator");
        TestUser reporter = createUser("tgtu_reporter", "user");
        TestUser target = createUser("tgtu_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());

        ResponseEntity<Map> response =
                get("/api/v1/admin/reports/" + reportId + "/target", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("reportType")).isEqualTo("user");
        assertThat(dataOf(response).get("entityId")).isEqualTo(target.id().toString());
        assertThat(dataOf(response).get("status")).isEqualTo("active");
    }

    @Test
    void getReportTarget_regularUser_returnsForbidden() {
        TestUser actor = createUser("tgtf_actor", "user");
        TestUser reporter = createUser("tgtf_reporter", "user");
        TestUser target = createUser("tgtf_target", "user");
        UUID reportId = insertReport(reporter.id(), target.id());

        assertThat(get("/api/v1/admin/reports/" + reportId + "/target", actor).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void blockUser(UUID blockerId, UUID blockedId) {
        jdbcTemplate.update(
                "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", blockerId, blockedId);
    }

    private UUID insertPostReport(UUID reporterId, UUID postId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reports "
                        + "(id, reporter_id, report_type, report_reason, entity_id, status) "
                        + "VALUES (?, ?, 'post', 'spam', ?, 'pending')",
                id,
                reporterId,
                postId);
        return id;
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
        return new TestUser(id, jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0));
    }

    private UUID insertPost(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status) "
                        + "VALUES (?, ?, 'Moderation target', 'text', 'published')",
                id,
                ownerId);
        return id;
    }

    private UUID insertPostWithCaption(UUID ownerId, String caption) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status) "
                        + "VALUES (?, ?, ?, 'text', 'published')",
                id,
                ownerId,
                caption);
        return id;
    }

    private UUID insertDraftPost(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status) "
                        + "VALUES (?, ?, 'Draft moderation target', 'text', 'draft')",
                id,
                ownerId);
        return id;
    }

    private void attachHashtag(UUID postId, String name) {
        UUID hashtagId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO hashtags (id, name) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                hashtagId,
                name);
        UUID resolved =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM hashtags WHERE name = ?", UUID.class, name);
        jdbcTemplate.update(
                "INSERT INTO post_hashtags (post_id, hashtag_id) VALUES (?, ?)", postId, resolved);
    }

    private int outboxCount(String eventType, UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND aggregate_id = ?",
                Integer.class,
                eventType,
                aggregateId);
    }

    private UUID insertComment(UUID postId, UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO comments "
                        + "(id, post_id, user_id, depth, content, moderation_status) "
                        + "VALUES (?, ?, ?, 0, 'Moderation target', 'approved')",
                id,
                postId,
                ownerId);
        return id;
    }

    private UUID insertReport(UUID reporterId, UUID targetId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reports "
                        + "(id, reporter_id, report_type, report_reason, entity_id, status) "
                        + "VALUES (?, ?, 'user', 'spam', ?, 'pending')",
                id,
                reporterId,
                targetId);
        return id;
    }

    private int auditCount(String actionType, UUID targetUserId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM admin_actions "
                        + "WHERE action_type = CAST(? AS admin_action_type) AND target_user_id = ?",
                Integer.class,
                actionType,
                targetUserId);
    }

    private ResponseEntity<Map> patch(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders(user)), Map.class);
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
    private static List<Map<?, ?>> contentOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<?, ?>>) data.get("content");
    }

    @Test
    void unauthenticatedRequest_isRejectedOnEveryModerationOperation() {
        // The cheapest possible guard against a future path-matcher change silently opening the
        // surface. Every 401 assertion in this module previously concerned token-epoch revocation;
        // none sent a request with no token at all.
        UUID any = UUID.randomUUID();

        assertThat(rest.getForEntity("/api/v1/admin/actions", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/admin/actions/" + any, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.getForEntity("/api/v1/admin/actions/for-user/" + any, Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.getForEntity("/api/v1/admin/reports/escalated/count", Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.getForEntity("/api/v1/admin/reports/" + any + "/target", Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        "/api/v1/admin/posts/" + any + "/remove",
                                        HttpMethod.PATCH,
                                        new HttpEntity<>(Map.of("reason", "no token")),
                                        Map.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void undeclaredQueryParameter_isRejectedOnEveryAuditListing() {
        // A client that misspells a filter must be told, not handed a full unfiltered page that it
        // then renders as if the filter had applied.
        TestUser admin = createUser("bogusparam_admin", "admin");

        assertThat(get("/api/v1/admin/actions?bogus=1", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        get("/api/v1/admin/actions/for-user/" + admin.id() + "?bogus=1", admin)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/api/v1/admin/reports/escalated/count?bogus=1", admin).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
