package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.app.testsupport.TestContainerImages;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
@AutoConfigureTestRestTemplate
class MessageRequestFoldersIT {

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
        registry.add("JWT_SECRET", () -> "message-folders-it-secret-32-chars!!!!!!");
        registry.add("JWT_ISSUER", () -> "https://message.it.local");
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
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    private record TestUser(UUID id, String token) {}

    @Test
    void primary_showsAcceptedAndOwnOutgoingPendingRequests() {
        TestUser alice = registerUser("fo_a");
        TestUser bob = registerUser("fo_b");
        TestUser carol = registerUser("fo_c");
        follow(carol, alice);
        UUID accepted = createDirectOk(alice, carol.id());
        UUID outgoing = createDirectOk(alice, bob.id());

        assertThat(folderIds(alice, "primary")).containsExactlyInAnyOrder(accepted, outgoing);
        assertThat(folderIds(alice, "requests")).isEmpty();
    }

    @Test
    void requests_showsIncomingPendingRequestsThatHaveAMessage() {
        TestUser alice = registerUser("fo_a");
        TestUser bob = registerUser("fo_b");
        UUID conversationId = createDirectOk(alice, bob.id());
        assertThat(send(alice, conversationId, "hello").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(folderIds(bob, "requests")).containsExactly(conversationId);
        assertThat(folderIds(bob, "primary")).doesNotContain(conversationId);
        assertThat(folderIds(bob, "hidden_requests")).isEmpty();
    }

    @Test
    void hiddenRequests_showsIncomingPendingHiddenRequests() {
        TestUser alice = registerUser("fo_a");
        TestUser eve = registerUser("fo_e");
        limitNonFollowers(eve);
        UUID conversationId = createDirectOk(alice, eve.id());
        assertThat(send(alice, conversationId, "hello").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(folderIds(eve, "hidden_requests")).containsExactly(conversationId);
        assertThat(folderIds(eve, "requests")).isEmpty();
        assertThat(folderIds(eve, "primary")).isEmpty();
    }

    @Test
    void emptyPendingConversation_appearsNowhereForTheRecipient() {
        TestUser alice = registerUser("fo_a");
        TestUser bob = registerUser("fo_b");
        createDirectOk(alice, bob.id());

        assertThat(folderIds(bob, "primary")).isEmpty();
        assertThat(folderIds(bob, "requests")).isEmpty();
        assertThat(folderIds(bob, "hidden_requests")).isEmpty();
    }

    @Test
    void list_unknownFolder_isRejected() {
        TestUser alice = registerUser("fo_a");

        ResponseEntity<Map> response = get(alice, "/api/v1/conversations?folder=archive");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private TestUser registerUser(String prefix) {
        String username = prefix + UUID.randomUUID().toString().substring(0, 6);
        String email = username + "@test.local";
        String password = "S3cur3P@ssword!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> registration =
                rest.exchange(
                        "/api/v1/auth/register",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of(
                                        "username", username,
                                        "email", email,
                                        "password", password,
                                        "displayName", username),
                                headers),
                        Map.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id =
                jdbc.queryForObject(
                        "SELECT id FROM users WHERE username = ?", UUID.class, username);
        jdbc.update("UPDATE user_credentials SET email_verified = TRUE WHERE user_id = ?", id);
        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new TestUser(
                id, (String) ((Map<?, ?>) login.getBody().get("data")).get("accessToken"));
    }

    private ResponseEntity<Map> createDirect(TestUser actor, UUID targetId) {
        return rest.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUserId", targetId), authHeaders(actor)),
                Map.class);
    }

    private UUID createDirectOk(TestUser actor, UUID targetId) {
        ResponseEntity<Map> response = createDirect(actor, targetId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    private static UUID idOf(ResponseEntity<Map> response) {
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private static Map<?, ?> dataOf(ResponseEntity<Map> response) {
        return (Map<?, ?>) response.getBody().get("data");
    }

    private ResponseEntity<Map> send(TestUser actor, UUID conversationId, String content) {
        return rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("messageType", "text", "content", content), authHeaders(actor)),
                Map.class);
    }

    private ResponseEntity<Map> get(TestUser actor, String path) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(actor)), Map.class);
    }

    private List<UUID> folderIds(TestUser actor, String folder) {
        ResponseEntity<Map> response =
                get(actor, "/api/v1/conversations?limit=100&folder=" + folder);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> content = (List<Map<?, ?>>) dataOf(response).get("content");
        return content.stream().map(row -> UUID.fromString((String) row.get("id"))).toList();
    }

    private void follow(TestUser follower, TestUser followed) {
        jdbc.update(
                "INSERT INTO follows(follower_id, following_id, status) VALUES (?, ?, 'accepted')",
                follower.id(),
                followed.id());
    }

    private void setPermission(TestUser user, String permission) {
        jdbc.update(
                "UPDATE user_settings SET message_permission = ?::message_permission WHERE user_id = ?",
                permission,
                user.id());
    }

    private void block(TestUser blocker, TestUser blocked) {
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                blocker.id(),
                blocked.id());
    }

    private void limitNonFollowers(TestUser user) {
        jdbc.update(
                "INSERT INTO interaction_limits(user_id, limit_non_followers, limit_recent_followers, expires_at)"
                        + " VALUES (?, TRUE, FALSE, NOW() + INTERVAL '1 day')",
                user.id());
    }

    private Map<String, Object> conversationRow(UUID conversationId) {
        return jdbc.queryForMap(
                "SELECT request_status::text AS status, request_recipient_id, request_hidden,"
                        + " direct_pair_key FROM conversations WHERE id = ?",
                conversationId);
    }

    private static HttpHeaders authHeaders(TestUser actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(actor.token());
        return headers;
    }
}
