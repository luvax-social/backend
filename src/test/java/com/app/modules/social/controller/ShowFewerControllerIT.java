package com.app.modules.social.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import tools.jackson.databind.json.JsonMapper;

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
class ShowFewerControllerIT {

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
        r.add("JWT_SECRET", () -> "show-fewer-controller-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://show-fewer-controller.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "Show Fewer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        // The publisher stays off so every enqueued event remains a PENDING row to inspect.
        r.add("app.outbox.publisher.enabled", () -> false);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() {
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void showFewer_publicAuthor_returns204StoresRowAndEnqueuesEvent() {
        Account viewer = account("sf_ok_v");
        Account author = account("sf_ok_a");

        ResponseEntity<String> response = send(HttpMethod.POST, path(author.id()), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(viewer.id(), author.id())).isEqualTo(1);
        List<Map<String, Object>> events = events("user.show-fewer.v1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("authorId", author.id().toString()).hasSize(1);
    }

    @Test
    void showFewer_repeat_isIdempotentWithoutSecondEvent() {
        Account viewer = account("sf_rep_v");
        Account author = account("sf_rep_a");
        send(HttpMethod.POST, path(author.id()), viewer.token());

        ResponseEntity<String> second = send(HttpMethod.POST, path(author.id()), viewer.token());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(viewer.id(), author.id())).isEqualTo(1);
        assertThat(events("user.show-fewer.v1")).hasSize(1);
    }

    @Test
    void showFewer_selfMissingAndBlockedTargets_answerTheIdenticalNotFoundBody() {
        Account viewer = account("sf_stealth_v");
        Account blocker = account("sf_stealth_b");
        Account blocked = account("sf_stealth_c");
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                blocker.id(),
                viewer.id());
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                viewer.id(),
                blocked.id());

        ResponseEntity<String> missing =
                send(HttpMethod.POST, path(UUID.randomUUID()), viewer.token());
        ResponseEntity<String> self = send(HttpMethod.POST, path(viewer.id()), viewer.token());
        ResponseEntity<String> blockedByTarget =
                send(HttpMethod.POST, path(blocker.id()), viewer.token());
        ResponseEntity<String> blockedByViewer =
                send(HttpMethod.POST, path(blocked.id()), viewer.token());

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (ResponseEntity<String> other : List.of(self, blockedByTarget, blockedByViewer)) {
            assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(stableBody(other)).isEqualTo(stableBody(missing));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM account_show_fewer WHERE user_id = ?",
                                Integer.class,
                                viewer.id()))
                .isZero();
        assertThat(events("user.show-fewer.v1")).isEmpty();
    }

    @Test
    void showFewer_softDeletedAuthor_answersTheIdenticalNotFoundBodyAsAMissingOne() {
        Account viewer = account("sf_gone_v");
        Account gone = account("sf_gone_a");
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", gone.id());

        ResponseEntity<String> missing =
                send(HttpMethod.POST, path(UUID.randomUUID()), viewer.token());
        ResponseEntity<String> softDeleted = send(HttpMethod.POST, path(gone.id()), viewer.token());

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(softDeleted.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stableBody(softDeleted)).isEqualTo(stableBody(missing));
        assertThat(rowCount(viewer.id(), gone.id())).isZero();
        assertThat(events("user.show-fewer.v1")).isEmpty();
    }

    @Test
    void undoShowFewer_rowExisted_returns204DeletesAndEnqueuesUndoneEvent() {
        Account viewer = account("sf_undo_v");
        Account author = account("sf_undo_a");
        send(HttpMethod.POST, path(author.id()), viewer.token());
        jdbc.update("DELETE FROM outbox_events");

        ResponseEntity<String> response =
                send(HttpMethod.DELETE, path(author.id()), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(viewer.id(), author.id())).isZero();
        List<Map<String, Object>> events = events("user.show-fewer-undone.v1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("authorId", author.id().toString()).hasSize(1);
    }

    @Test
    void undoShowFewer_noRow_returns204WithoutEvent() {
        Account viewer = account("sf_undo_none");

        ResponseEntity<String> response =
                send(HttpMethod.DELETE, path(UUID.randomUUID()), viewer.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(events("user.show-fewer-undone.v1")).isEmpty();
    }

    @Test
    void withoutToken_bothVerbsReturn401() {
        assertThat(send(HttpMethod.POST, path(UUID.randomUUID()), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(HttpMethod.DELETE, path(UUID.randomUUID()), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void profileRead_viewerSeesIsShowingFewer_authorSeesNone() {
        Account viewer = account("sf_flag_v");
        Account author = account("sf_flag_a");
        send(HttpMethod.POST, path(author.id()), viewer.token());

        Map<?, ?> seenByViewer = viewerState(author.id(), viewer.token());
        Map<?, ?> seenByAuthor = viewerState(viewer.id(), author.token());

        assertThat(seenByViewer.get("isShowingFewer")).isEqualTo(true);
        assertThat(seenByViewer.get("isMutingPosts")).isEqualTo(false);
        assertThat(seenByAuthor.get("isShowingFewer")).isEqualTo(false);
    }

    private static String path(UUID authorId) {
        return "/api/v1/social/show-fewer/" + authorId;
    }

    private int rowCount(UUID userId, UUID authorId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM account_show_fewer WHERE user_id = ? AND author_id = ?",
                Integer.class,
                userId,
                authorId);
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(null, headers), String.class);
    }

    private Map<?, ?> viewerState(UUID profileId, String token) {
        ResponseEntity<String> response = send(HttpMethod.GET, "/api/v1/users/" + profileId, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) JSON.readValue(response.getBody(), Map.class).get("data");
        return (Map<?, ?>) data.get("viewerState");
    }

    // The envelope carries a timestamp that differs per response, so two answers are compared on
    // the whole body except that, which keeps a field added to the error later inside the check.
    private Map<?, ?> stableBody(ResponseEntity<String> response) {
        Map<?, ?> body =
                new java.util.LinkedHashMap<>(JSON.readValue(response.getBody(), Map.class));
        body.remove("timestamp");
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(String eventType) {
        return jdbc
                .queryForList(
                        "SELECT payload::text FROM outbox_events WHERE event_type = ? ORDER BY"
                                + " created_at",
                        String.class,
                        eventType)
                .stream()
                .map(json -> (Map<String, Object>) JSON.readValue(json, Map.class).get("data"))
                .toList();
    }

    private record Account(UUID id, String token) {}

    @SuppressWarnings("unchecked")
    private Account account(String username) {
        String email = username + "_" + UUID.randomUUID().toString().substring(0, 6) + "@sf.test";
        String password = "Password1!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(
                "/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("username", username, "email", email, "password", password),
                        headers),
                Map.class);
        UUID userId = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow().getId();
        rest.getForEntity(
                "/api/v1/auth/verify-email?token="
                        + tokenService.createEmailVerificationToken(userId),
                Map.class);
        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        Map<String, String> data = (Map<String, String>) login.getBody().get("data");
        return new Account(userId, data.get("accessToken"));
    }
}
