package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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
class MessageReactionTombstoneIT {

    private static final String THUMBS_UP = "👍";
    private static final String HEART = "❤️";

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
        registry.add("JWT_SECRET", () -> "message-reaction-tombstone-it-secret-32-chars!");
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

    private record Chat(UUID id, TestUser alice, TestUser bob) {}

    @Test
    void react_senderDeletedMessage_answersMessageNotFound() {
        Chat chat = openChat("tomb_deleted");
        UUID message = sendId(chat.bob(), chat.id(), "soon deleted");
        assertThat(deleteMessage(chat.bob(), chat.id(), message).is2xxSuccessful()).isTrue();

        ResponseEntity<Map> response = put(chat.alice(), chat.id(), message, THUMBS_UP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_NOT_FOUND");
        assertThat(reactionRows(message)).isZero();
    }

    @Test
    void react_adminRemovedMessage_answersMessageNotFound() {
        Chat chat = openChat("tomb_removed");
        UUID message = sendId(chat.bob(), chat.id(), "soon removed");
        jdbc.update("UPDATE messages SET admin_removed_at = NOW() WHERE id = ?", message);

        ResponseEntity<Map> response = put(chat.alice(), chat.id(), message, THUMBS_UP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_NOT_FOUND");
        assertThat(reactionRows(message)).isZero();
    }

    @Test
    void history_adminRemovedMessage_hidesReactionsUntilRestored() {
        Chat chat = openChat("tomb_restore");
        UUID message = sendId(chat.bob(), chat.id(), "moderated then restored");
        put(chat.alice(), chat.id(), message, THUMBS_UP);
        put(chat.bob(), chat.id(), message, HEART);
        assertThat(reactionsOf(chat.alice(), chat.id(), message)).hasSize(2);

        jdbc.update("UPDATE messages SET admin_removed_at = NOW() WHERE id = ?", message);
        assertThat(reactionsOf(chat.alice(), chat.id(), message)).isEmpty();
        assertThat(reactionsOf(chat.bob(), chat.id(), message)).isEmpty();

        jdbc.update("UPDATE messages SET admin_removed_at = NULL WHERE id = ?", message);
        assertThat(reactionsOf(chat.alice(), chat.id(), message))
                .extracting(r -> r.get("emoji"))
                .containsExactly(THUMBS_UP, HEART);
    }

    @Test
    void history_senderDeletedMessage_hidesReactionsForGood() {
        Chat chat = openChat("tomb_gone");
        UUID message = sendId(chat.bob(), chat.id(), "deleted by its sender");
        put(chat.alice(), chat.id(), message, THUMBS_UP);

        assertThat(deleteMessage(chat.bob(), chat.id(), message).is2xxSuccessful()).isTrue();
        assertThat(reactionsOf(chat.alice(), chat.id(), message)).isEmpty();

        // An administrative restore clears only the moderation tombstone, so the sender's own
        // deletion keeps the reactions hidden.
        jdbc.update("UPDATE messages SET admin_removed_at = NULL WHERE id = ?", message);
        assertThat(reactionsOf(chat.alice(), chat.id(), message)).isEmpty();
    }

    @Test
    void unreact_tombstonedMessage_isANoOpThatKeepsTheRowForARestore() {
        Chat chat = openChat("tomb_unreact");
        UUID message = sendId(chat.bob(), chat.id(), "removed while reacted");
        put(chat.alice(), chat.id(), message, THUMBS_UP);
        jdbc.update("UPDATE messages SET admin_removed_at = NOW() WHERE id = ?", message);
        int eventsBefore = reactionEvents(message);

        ResponseEntity<Map> response = delete(chat.alice(), chat.id(), message);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reactionRows(message)).isEqualTo(1);
        assertThat(reactionEvents(message)).isEqualTo(eventsBefore);
        jdbc.update("UPDATE messages SET admin_removed_at = NULL WHERE id = ?", message);
        assertThat(reactionsOf(chat.alice(), chat.id(), message)).hasSize(1);
    }

    private Chat openChat(String prefix) {
        TestUser alice = registerUser(prefix + "_alice");
        TestUser bob = registerUser(prefix + "_bob");
        ResponseEntity<Map> created =
                rest.exchange(
                        "/api/v1/conversations",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("targetUserId", bob.id()), authHeaders(alice)),
                        Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) ((Map<?, ?>) created.getBody().get("data")).get("id"));
        jdbc.update(
                "UPDATE conversations SET request_status = 'accepted', request_hidden = FALSE,"
                        + " request_recipient_id = NULL WHERE id = ?",
                id);
        return new Chat(id, alice, bob);
    }

    private UUID sendId(TestUser actor, UUID conversationId, String content) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/messages",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("messageType", "text", "content", content),
                                authHeaders(actor)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private HttpStatus deleteMessage(TestUser actor, UUID conversationId, UUID messageId) {
        return HttpStatus.valueOf(
                rest.exchange(
                                "/api/v1/conversations/"
                                        + conversationId
                                        + "/messages/"
                                        + messageId,
                                HttpMethod.DELETE,
                                new HttpEntity<>(authHeaders(actor)),
                                Map.class)
                        .getStatusCode()
                        .value());
    }

    private ResponseEntity<Map> put(
            TestUser actor, UUID conversationId, UUID messageId, String emoji) {
        Map<String, Object> body = new HashMap<>();
        body.put("emoji", emoji);
        return rest.exchange(
                reactionUrl(conversationId, messageId),
                HttpMethod.PUT,
                new HttpEntity<>(body, authHeaders(actor)),
                Map.class);
    }

    private ResponseEntity<Map> delete(TestUser actor, UUID conversationId, UUID messageId) {
        return rest.exchange(
                reactionUrl(conversationId, messageId),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(actor)),
                Map.class);
    }

    private static String reactionUrl(UUID conversationId, UUID messageId) {
        return "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/reaction";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> reactionsOf(
            TestUser viewer, UUID conversationId, UUID messageId) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/messages?limit=100",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(viewer)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> messages =
                (List<Map<String, Object>>)
                        ((Map<?, ?>) response.getBody().get("data")).get("content");
        return (List<Map<String, Object>>)
                messages.stream()
                        .filter(m -> messageId.toString().equals(m.get("id")))
                        .findFirst()
                        .orElseThrow()
                        .get("reactions");
    }

    private int reactionRows(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?",
                Integer.class,
                messageId);
    }

    private int reactionEvents(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                        + " 'message.reaction.changed.v1' AND aggregate_id = ?",
                Integer.class,
                messageId);
    }

    private TestUser registerUser(String username) {
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

    private static HttpHeaders authHeaders(TestUser actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(actor.token());
        return headers;
    }
}
