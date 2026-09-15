package com.app.modules.report.controller;

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
import com.app.modules.report.entity.Report;
import com.app.modules.report.enums.ReportStatus;
import com.app.modules.report.repository.ReportRepository;

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
class ReportControllerIT {

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
        registry.add("JWT_SECRET", () -> "report-controller-it-secret-32-chars-minimum!!");
        registry.add("JWT_ISSUER", () -> "https://report.it.local");
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
    @Autowired private ReportRepository reportRepository;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM reports");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void submitReport_validUserTarget_returnsCreatedAndPersistsReport() {
        TestUser reporter = createUser("submit_reporter", "user");
        TestUser target = createUser("submit_target", "user");

        ResponseEntity<Map> response =
                submitReport(reporter, target.id(), "spam", "Repeated advertisements");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        UUID reportId = UUID.fromString((String) data.get("id"));
        assertThat(data.get("status")).isEqualTo("pending");
        Report persisted = reportRepository.findById(reportId).orElseThrow();
        assertThat(persisted.getReporterId()).isEqualTo(reporter.id());
        assertThat(persisted.getEntityId()).isEqualTo(target.id());
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.PENDING);
    }

    @Test
    void submitReport_duplicateTarget_returnsConflict() {
        TestUser reporter = createUser("duplicate_reporter", "user");
        TestUser target = createUser("duplicate_target", "user");
        assertThat(submitReport(reporter, target.id(), "spam", null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> duplicate = submitReport(reporter, target.id(), "harassment", null);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("code")).isEqualTo("REPORT_DUPLICATE");
        assertThat(reportRepository.count()).isEqualTo(1);
    }

    @Test
    void submitReport_sameTargetAfterTerminalReport_returnsCreated() {
        TestUser reporter = createUser("terminal_duplicate_reporter", "user");
        TestUser target = createUser("terminal_duplicate_target", "user");
        ResponseEntity<Map> first = submitReport(reporter, target.id(), "spam", null);
        UUID reportId =
                UUID.fromString((String) ((Map<?, ?>) first.getBody().get("data")).get("id"));
        jdbcTemplate.update("UPDATE reports SET status = 'dismissed' WHERE id = ?", reportId);

        ResponseEntity<Map> second = submitReport(reporter, target.id(), "harassment", null);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reportRepository.count()).isEqualTo(2);
    }

    @Test
    void submitReport_selfTarget_returnsBadRequest() {
        TestUser reporter = createUser("self_reporter", "user");

        ResponseEntity<Map> response = submitReport(reporter, reporter.id(), "other", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_SELF_NOT_ALLOWED");
    }

    @Test
    void listReports_regularUser_returnsForbidden() {
        TestUser user = createUser("list_user", "user");

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void listReports_sizeAboveMax_returnsBadRequest() {
        TestUser moderator = createUser("list_size_moderator", "moderator");

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports?limit=999", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listReports_misspelledFilter_isRejectedRatherThanAnsweredUnfiltered() {
        TestUser moderator = createUser("strict_list_moderator", "moderator");

        // "statuss" is not "status". Before this endpoint became strict the parameter was dropped
        // and an unfiltered page came back, which a reviewer then works through believing it is
        // the set they asked for.
        ResponseEntity<Map> response = getWithAuth("/api/v1/reports?statuss=pending", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listReports_declaredFilters_areStillAccepted() {
        TestUser moderator = createUser("strict_declared_moderator", "moderator");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/reports?status=pending&reportType=post&limit=5", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getPendingReports_unknownParameter_isRejected() {
        TestUser moderator = createUser("strict_pending_moderator", "moderator");

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/pending?page=2", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getPendingReports_regularUser_returnsForbidden() {
        TestUser user = createUser("pending_forbidden_user", "user");

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/pending", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void updateStatus_regularUser_returnsForbidden() {
        TestUser user = createUser("status_forbidden_user", "user");

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/reports/" + UUID.randomUUID() + "/status",
                        Map.of("status", "reviewing"),
                        user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reviewWorkflow_moderator_listsGetsAndResolvesReport() {
        TestUser reporter = createUser("workflow_reporter", "user");
        TestUser target = createUser("workflow_target", "user");
        TestUser moderator = createUser("workflow_moderator", "moderator");
        ResponseEntity<Map> submitted = submitReport(reporter, target.id(), "scam", "Fake offer");
        UUID reportId =
                UUID.fromString((String) ((Map<?, ?>) submitted.getBody().get("data")).get("id"));

        ResponseEntity<Map> list =
                getWithAuth("/api/v1/reports?status=pending&reportType=user", moderator);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contentOf(list)).hasSize(1);

        ResponseEntity<Map> detail = getWithAuth("/api/v1/reports/" + reportId, moderator);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> reviewing =
                patchWithAuth(
                        "/api/v1/reports/" + reportId + "/status",
                        Map.of("status", "reviewing"),
                        moderator);
        assertThat(reviewing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) reviewing.getBody().get("data")).get("status"))
                .isEqualTo("reviewing");

        // Closing the report is a moderation decision and belongs to the audited admin endpoint;
        // this one refuses it.
        ResponseEntity<Map> resolved =
                patchWithAuth(
                        "/api/v1/reports/" + reportId + "/status",
                        Map.of("status", "resolved", "resolutionNote", "Confirmed violation"),
                        moderator);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Report persisted = reportRepository.findById(reportId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(ReportStatus.REVIEWING);
        assertThat(persisted.getReviewedBy()).isEqualTo(moderator.id());
        assertThat(persisted.getReviewedAt()).isNotNull();
    }

    @Test
    void getPendingReports_moderator_returnsOldestReportsFirst() {
        TestUser reporter = createUser("pending_reporter", "user");
        TestUser firstTarget = createUser("pending_target_first", "user");
        TestUser secondTarget = createUser("pending_target_second", "user");
        TestUser moderator = createUser("pending_moderator", "moderator");
        UUID firstReport = insertReport(reporter.id(), firstTarget.id(), "2026-07-11T08:00:00Z");
        UUID secondReport = insertReport(reporter.id(), secondTarget.id(), "2026-07-11T09:00:00Z");

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/pending?limit=2", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> content = contentOf(response);
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("id")).isEqualTo(firstReport.toString());
        assertThat(content.get(1).get("id")).isEqualTo(secondReport.toString());
    }

    @Test
    void updateStatus_pendingToResolved_returnsConflict() {
        TestUser reporter = createUser("note_reporter", "user");
        TestUser target = createUser("note_target", "user");
        TestUser moderator = createUser("note_moderator", "moderator");
        ResponseEntity<Map> submitted = submitReport(reporter, target.id(), "violence", null);
        UUID reportId =
                UUID.fromString((String) ((Map<?, ?>) submitted.getBody().get("data")).get("id"));

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/reports/" + reportId + "/status",
                        Map.of("status", "resolved", "resolutionNote", "Confirmed violation"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_INVALID_TRANSITION");
    }

    @Test
    void updateStatus_pendingToDismissed_returnsConflict() {
        TestUser reporter = createUser("dismiss_note_reporter", "user");
        TestUser target = createUser("dismiss_note_target", "user");
        TestUser moderator = createUser("dismiss_note_moderator", "moderator");
        ResponseEntity<Map> submitted = submitReport(reporter, target.id(), "scam", null);
        UUID reportId =
                UUID.fromString((String) ((Map<?, ?>) submitted.getBody().get("data")).get("id"));

        ResponseEntity<Map> response =
                patchWithAuth(
                        "/api/v1/reports/" + reportId + "/status",
                        Map.of("status", "dismissed", "resolutionNote", "Not actionable"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_INVALID_TRANSITION");
    }

    @Test
    void getReport_moderatorAndResolvedReportItDidNotEscalate_returnsNotFound() {
        TestUser reporter = createUser("closed_read_reporter", "user");
        TestUser target = createUser("closed_read_target", "user");
        TestUser moderator = createUser("closed_read_moderator", "moderator");
        UUID reportId = insertReport(reporter.id(), target.id(), "resolved", null);

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/" + reportId, moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_NOT_FOUND");
    }

    @Test
    void getReport_moderatorAndDismissedReportItDidNotEscalate_returnsNotFound() {
        TestUser reporter = createUser("dismissed_read_reporter", "user");
        TestUser target = createUser("dismissed_read_target", "user");
        TestUser moderator = createUser("dismissed_read_moderator", "moderator");
        UUID reportId = insertReport(reporter.id(), target.id(), "dismissed", null);

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/" + reportId, moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_NOT_FOUND");
    }

    @Test
    void getReport_moderatorAndReportAnotherModeratorEscalated_returnsNotFound() {
        TestUser reporter = createUser("other_esc_reporter", "user");
        TestUser target = createUser("other_esc_target", "user");
        TestUser escalator = createUser("other_esc_escalator", "moderator");
        TestUser moderator = createUser("other_esc_moderator", "moderator");
        UUID reportId = insertReport(reporter.id(), target.id(), "escalated", escalator.id());

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/" + reportId, moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("REPORT_NOT_FOUND");
    }

    @Test
    void getReport_moderatorAndReportItEscalatedItself_returnsOk() {
        TestUser reporter = createUser("own_esc_reporter", "user");
        TestUser target = createUser("own_esc_target", "user");
        TestUser moderator = createUser("own_esc_moderator", "moderator");
        UUID reportId = insertReport(reporter.id(), target.id(), "escalated", moderator.id());

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/" + reportId, moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("id")).isEqualTo(reportId.toString());
    }

    @Test
    void getReport_moderatorAndResolvedReportItEscalated_returnsOk() {
        TestUser reporter = createUser("closed_own_reporter", "user");
        TestUser target = createUser("closed_own_target", "user");
        TestUser moderator = createUser("closed_own_moderator", "moderator");
        UUID reportId = insertReport(reporter.id(), target.id(), "resolved", moderator.id());

        ResponseEntity<Map> response = getWithAuth("/api/v1/reports/" + reportId, moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getReport_moderatorAndOpenReport_returnsOk() {
        TestUser reporter = createUser("open_read_reporter", "user");
        TestUser pendingTarget = createUser("open_read_target_a", "user");
        TestUser reviewingTarget = createUser("open_read_target_b", "user");
        TestUser moderator = createUser("open_read_moderator", "moderator");
        UUID pendingId = insertReport(reporter.id(), pendingTarget.id(), "pending", null);
        UUID reviewingId = insertReport(reporter.id(), reviewingTarget.id(), "reviewing", null);

        assertThat(getWithAuth("/api/v1/reports/" + pendingId, moderator).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(getWithAuth("/api/v1/reports/" + reviewingId, moderator).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void getReport_administrator_readsEveryStatus() {
        TestUser reporter = createUser("admin_read_reporter", "user");
        TestUser admin = createUser("admin_read_admin", "admin");
        TestUser escalator = createUser("admin_read_escalator", "moderator");
        for (String status : List.of("pending", "reviewing", "resolved", "dismissed")) {
            TestUser target = createUser("admin_read_target_" + status, "user");
            UUID reportId = insertReport(reporter.id(), target.id(), status, null);
            assertThat(getWithAuth("/api/v1/reports/" + reportId, admin).getStatusCode())
                    .as("administrator reading a %s report", status)
                    .isEqualTo(HttpStatus.OK);
        }
        TestUser escalatedTarget = createUser("admin_read_target_escalated", "user");
        UUID escalatedId =
                insertReport(reporter.id(), escalatedTarget.id(), "escalated", escalator.id());
        assertThat(getWithAuth("/api/v1/reports/" + escalatedId, admin).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private TestUser createUser(String username, String role) {
        UUID id = UUID.randomUUID();
        String email = username + "@test.local";
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email, role, status, is_private, is_verified) "
                        + "VALUES (?, ?, ?, CAST(? AS user_role), 'active', FALSE, TRUE)",
                id,
                username,
                email,
                role);
        String token = jwtTokenProvider.generateAccessToken(id, role.toUpperCase(), 0);
        return new TestUser(id, token);
    }

    private ResponseEntity<Map> submitReport(
            TestUser reporter, UUID targetId, String reason, String description) {
        java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
        payload.put("reportType", "user");
        payload.put("reportReason", reason);
        payload.put("entityId", targetId.toString());
        if (description != null) {
            payload.put("description", description);
        }
        return rest.exchange(
                "/api/v1/reports",
                HttpMethod.POST,
                new HttpEntity<>(payload, authHeaders(reporter)),
                Map.class);
    }

    private UUID insertReport(UUID reporterId, UUID targetId, String createdAt) {
        UUID reportId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reports "
                        + "(id, reporter_id, report_type, report_reason, entity_id, status, "
                        + "created_at) "
                        + "VALUES (?, ?, 'user', 'spam', ?, 'pending', CAST(? AS timestamptz))",
                reportId,
                reporterId,
                targetId,
                createdAt);
        return reportId;
    }

    private UUID insertReport(UUID reporterId, UUID targetId, String status, UUID escalatedBy) {
        UUID reportId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reports "
                        + "(id, reporter_id, report_type, report_reason, entity_id, status, "
                        + "escalated_by) "
                        + "VALUES (?, ?, 'user', 'spam', ?, CAST(? AS report_status), ?)",
                reportId,
                reporterId,
                targetId,
                status,
                escalatedBy);
        return reportId;
    }

    private ResponseEntity<Map> getWithAuth(String path, TestUser user) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private ResponseEntity<Map> patchWithAuth(String path, Object body, TestUser user) {
        return rest.exchange(
                path, HttpMethod.PATCH, new HttpEntity<>(body, authHeaders(user)), Map.class);
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> contentOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<?, ?>>) data.get("content");
    }
}
