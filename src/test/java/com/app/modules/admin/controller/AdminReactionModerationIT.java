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
import com.app.testsupport.TestContainerImages;

/**
 * Moderation of message reactions: a moderator removes and restores a reaction by its id, the
 * linked report is closed, the reactor is notified and mailed, and both participants stop seeing
 * the reaction while the removal stands.
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
class AdminReactionModerationIT {

    private static final String THUMBS_UP = "👍";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("TURNSTILE_AUTH_ENABLED", () -> false);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "admin-reaction-moderation-it-secret-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://admin-reaction.it.local");
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

    private record Fixture(
            TestUser reactor, TestUser peer, UUID conversation, UUID message, UUID reaction) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM admin_actions");
        jdbcTemplate.update("DELETE FROM reports");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void removeReaction_moderatorWithLinkedReport_hidesItClosesTheReportAndNotifiesTheReactor() {
        TestUser moderator = createUser("rxm_mod", "moderator");
        Fixture fixture = fixture();
        UUID reportId = insertReport(fixture.peer(), fixture.reaction());

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/message-reactions/" + fixture.reaction() + "/remove",
                        Map.of("reason", "Harassing emoji", "reportId", reportId.toString()),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> action = dataOf(response);
        assertThat(action.get("actionType")).isEqualTo("remove_message_reaction");
        assertThat(action.get("targetUserId")).isEqualTo(fixture.reactor().id().toString());
        assertThat(action.get("targetEntityType")).isEqualTo("message_reaction");
        assertThat(action.get("targetEntityId")).isEqualTo(fixture.reaction().toString());
        assertThat(action.get("reportId")).isEqualTo(reportId.toString());
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT status::text FROM reports WHERE id = ?",
                                String.class,
                                reportId))
                .isEqualTo("resolved");
        assertThat(reactionsSeenBy(fixture.reactor(), fixture)).isEmpty();
        assertThat(reactionsSeenBy(fixture.peer(), fixture)).isEmpty();
        assertThat(removalNotices(fixture.reactor())).isEqualTo(1);
        assertThat(removalNotices(fixture.peer())).isZero();
    }

    @Test
    void removeReaction_queuesAnAppealableNoticeForTheReactor() {
        TestUser moderator = createUser("rxm_mail_mod", "moderator");
        Fixture fixture = fixture();

        patch(
                "/api/v1/admin/message-reactions/" + fixture.reaction() + "/remove",
                Map.of("reason", "Harassing emoji"),
                moderator);

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                                        + " 'admin.moderation-notice.requested.v1'"
                                        + " AND payload::text LIKE '%REMOVE_MESSAGE_REACTION%'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void restoreReaction_afterRemoval_clearsTheTombstoneAuditsAndShowsItAgain() {
        TestUser moderator = createUser("rxm_restore_mod", "moderator");
        Fixture fixture = fixture();
        patch(
                "/api/v1/admin/message-reactions/" + fixture.reaction() + "/remove",
                Map.of("reason", "Harassing emoji"),
                moderator);

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/message-reactions/" + fixture.reaction() + "/restore",
                        Map.of("reason", "Appeal accepted"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("actionType")).isEqualTo("restore_message_reaction");
        assertThat(adminRemovedAt(fixture.reaction())).isNull();
        assertThat(reactionsSeenBy(fixture.peer(), fixture)).hasSize(1);
        assertThat(reactionsSeenBy(fixture.reactor(), fixture)).hasSize(1);
    }

    @Test
    void restoreReaction_notRemoved_isAnInvalidTransition() {
        TestUser moderator = createUser("rxm_invalid_mod", "moderator");
        Fixture fixture = fixture();

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/message-reactions/" + fixture.reaction() + "/restore",
                        Map.of("reason", "Appeal accepted"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("ADMIN_INVALID_TRANSITION");
    }

    @Test
    void removeReaction_withoutAReason_isRejected() {
        TestUser moderator = createUser("rxm_reason_mod", "moderator");
        Fixture fixture = fixture();

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/message-reactions/" + fixture.reaction() + "/remove",
                        Map.of(),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(adminRemovedAt(fixture.reaction())).isNull();
    }

    @Test
    void removeReaction_unknownReaction_isNotFound() {
        TestUser moderator = createUser("rxm_missing_mod", "moderator");

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/message-reactions/" + UUID.randomUUID() + "/remove",
                        Map.of("reason", "Harassing emoji"),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void removeReaction_ordinaryUser_isForbidden() {
        Fixture fixture = fixture();

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/message-reactions/" + fixture.reaction() + "/remove",
                        Map.of("reason", "Harassing emoji"),
                        fixture.peer());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(adminRemovedAt(fixture.reaction())).isNull();
    }

    @Test
    void removeReaction_reportOfAnotherTarget_isRefusedAndChangesNothing() {
        TestUser moderator = createUser("rxm_wrong_report_mod", "moderator");
        Fixture fixture = fixture();
        Fixture other = fixture();
        UUID reportId = insertReport(other.peer(), other.reaction());

        ResponseEntity<Map> response =
                patch(
                        "/api/v1/admin/message-reactions/" + fixture.reaction() + "/remove",
                        Map.of("reason", "Harassing emoji", "reportId", reportId.toString()),
                        moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(adminRemovedAt(fixture.reaction())).isNull();
    }

    @Test
    void reportTarget_reaction_carriesTheEmojiTheReactorAndAMessageSnippet() {
        TestUser moderator = createUser("rxm_target_mod", "moderator");
        Fixture fixture = fixture();
        UUID reportId = insertReport(fixture.peer(), fixture.reaction());

        ResponseEntity<Map> response =
                get("/api/v1/admin/reports/" + reportId + "/target", moderator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> target = dataOf(response);
        assertThat(target.get("reportType")).isEqualTo("message_reaction");
        assertThat(target.get("entityId")).isEqualTo(fixture.reaction().toString());
        assertThat(target.get("emoji")).isEqualTo(THUMBS_UP);
        assertThat(target.get("ownerId")).isEqualTo(fixture.reactor().id().toString());
        assertThat(target.get("ownerUsername")).isNotNull();
        assertThat(target.get("text")).isEqualTo("react to this message");
        assertThat(target.get("removed")).isEqualTo(false);
    }

    @Test
    void reportTarget_removedReaction_isMarkedRemoved() {
        TestUser moderator = createUser("rxm_gone_mod", "moderator");
        Fixture fixture = fixture();
        UUID reportId = insertReport(fixture.peer(), fixture.reaction());
        jdbcTemplate.update(
                "UPDATE message_reactions SET admin_removed_at = NOW() WHERE id = ?",
                fixture.reaction());

        ResponseEntity<Map> response =
                get("/api/v1/admin/reports/" + reportId + "/target", moderator);

        assertThat(dataOf(response).get("removed")).isEqualTo(true);
    }

    private Fixture fixture() {
        TestUser reactor = createUser("rxm_reactor", "user");
        TestUser peer = createUser("rxm_peer", "user");
        UUID conversation =
                jdbcTemplate.queryForObject(
                        "INSERT INTO conversations DEFAULT VALUES RETURNING id", UUID.class);
        for (TestUser member : List.of(reactor, peer)) {
            jdbcTemplate.update(
                    "INSERT INTO conversation_participants(conversation_id, user_id)"
                            + " VALUES (?, ?)",
                    conversation,
                    member.id());
        }
        UUID message =
                jdbcTemplate.queryForObject(
                        "INSERT INTO messages(conversation_id, sender_id, message_type, content)"
                                + " VALUES (?, ?, 'text', 'react to this message') RETURNING id",
                        UUID.class,
                        conversation,
                        peer.id());
        UUID reaction =
                jdbcTemplate.queryForObject(
                        "INSERT INTO message_reactions(message_id, user_id, emoji)"
                                + " VALUES (?, ?, ?) RETURNING id",
                        UUID.class,
                        message,
                        reactor.id(),
                        THUMBS_UP);
        return new Fixture(reactor, peer, conversation, message, reaction);
    }

    private UUID insertReport(TestUser reporter, UUID reactionId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reports (id, reporter_id, report_type, report_reason, entity_id,"
                        + " status) VALUES (?, ?, 'message_reaction', 'harassment', ?, 'pending')",
                id,
                reporter.id(),
                reactionId);
        return id;
    }

    private Object adminRemovedAt(UUID reactionId) {
        return jdbcTemplate.queryForObject(
                "SELECT admin_removed_at FROM message_reactions WHERE id = ?",
                java.sql.Timestamp.class,
                reactionId);
    }

    private int removalNotices(TestUser recipient) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ?"
                        + " AND type = 'reaction_removed'",
                Integer.class,
                recipient.id());
    }

    @SuppressWarnings("unchecked")
    private List<Map<?, ?>> reactionsSeenBy(TestUser viewer, Fixture fixture) {
        ResponseEntity<Map> response =
                get("/api/v1/conversations/" + fixture.conversation() + "/messages", viewer);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> messages = (List<Map<?, ?>>) dataOf(response).get("content");
        return messages.stream()
                .filter(row -> fixture.message().toString().equals(row.get("id")))
                .flatMap(row -> ((List<Map<?, ?>>) row.get("reactions")).stream())
                .toList();
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
}
