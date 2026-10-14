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
class ConversationReactionPreviewIT {

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
        registry.add("JWT_SECRET", () -> "conversation-reaction-preview-it-secret-32-chars");
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

    private record Chat(UUID id, TestUser alice, TestUser other) {}

    @Test
    void list_reactionNewerThanLastMessage_showsLastReactionWithoutTouchingOrderOrUnread() {
        TestUser alice = registerUser("prev_main_alice");
        Chat withBob = openAcceptedChat(alice, registerUser("prev_main_bob"));
        UUID aliceMessage = sendId(alice, withBob.id(), "from alice");
        ageMessage(aliceMessage);
        Chat withCarol = openAcceptedChat(alice, registerUser("prev_main_carol"));
        UUID carolMessage = sendId(withCarol.other(), withCarol.id(), "from carol");
        ageMessage(carolMessage);
        List<String> orderBefore = conversationIds(alice);
        Map<String, Object> bobRowBefore = row(withBob.other(), withBob.id());
        assertThat(orderBefore).containsExactly(withCarol.id().toString(), withBob.id().toString());

        assertThat(put(withBob.other(), withBob.id(), aliceMessage, THUMBS_UP).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(conversationIds(alice)).isEqualTo(orderBefore);
        Map<String, Object> aliceRow = row(alice, withBob.id());
        Map<?, ?> last = (Map<?, ?>) aliceRow.get("lastReaction");
        assertThat(last.get("userId")).isEqualTo(withBob.other().id().toString());
        assertThat(last.get("emoji")).isEqualTo(THUMBS_UP);
        assertThat(last.get("messageId")).isEqualTo(aliceMessage.toString());
        assertThat(last.get("onMyMessage")).isEqualTo(true);
        assertThat(last.get("reactedAt")).isNotNull();
        assertThat(aliceRow.get("unreadCount")).isEqualTo(0);
        Map<String, Object> bobRow = row(withBob.other(), withBob.id());
        assertThat(bobRow.get("unreadCount")).isEqualTo(bobRowBefore.get("unreadCount"));
        assertThat(((Map<?, ?>) bobRow.get("lastReaction")).get("onMyMessage")).isEqualTo(false);
        assertThat(row(alice, withCarol.id()).get("lastReaction")).isNull();
    }

    @Test
    void list_newestOfSeveralReactions_isTheOneShown() {
        TestUser alice = registerUser("prev_newest_alice");
        Chat chat = openAcceptedChat(alice, registerUser("prev_newest_bob"));
        UUID first = sendId(alice, chat.id(), "first");
        UUID second = sendId(chat.other(), chat.id(), "second");
        ageMessage(first);
        ageMessage(second);
        put(chat.other(), chat.id(), first, THUMBS_UP);
        put(alice, chat.id(), second, HEART);
        jdbc.update(
                "UPDATE message_reactions SET reacted_at = NOW() - interval '1 minute' WHERE"
                        + " message_id = ?",
                first);

        Map<?, ?> last = (Map<?, ?>) row(alice, chat.id()).get("lastReaction");

        assertThat(last.get("emoji")).isEqualTo(HEART);
        assertThat(last.get("messageId")).isEqualTo(second.toString());
        assertThat(last.get("onMyMessage")).isEqualTo(false);
    }

    @Test
    void list_reactionOlderThanLastMessage_isLeftOut() {
        TestUser alice = registerUser("prev_old_alice");
        Chat chat = openAcceptedChat(alice, registerUser("prev_old_bob"));
        UUID message = sendId(alice, chat.id(), "reacted to");
        ageMessage(message);
        put(chat.other(), chat.id(), message, THUMBS_UP);
        jdbc.update(
                "UPDATE message_reactions SET reacted_at = NOW() - interval '1 hour' WHERE"
                        + " message_id = ?",
                message);
        sendId(chat.other(), chat.id(), "a later message");

        assertThat(row(alice, chat.id()).get("lastReaction")).isNull();
    }

    @Test
    void list_tombstonedBlockedOrRemovedReaction_isLeftOut() {
        TestUser alice = registerUser("prev_hide_alice");
        Chat chat = openAcceptedChat(alice, registerUser("prev_hide_bob"));
        UUID message = sendId(alice, chat.id(), "will be hidden");
        ageMessage(message);
        put(chat.other(), chat.id(), message, THUMBS_UP);
        assertThat(row(alice, chat.id()).get("lastReaction")).isNotNull();

        jdbc.update("UPDATE messages SET admin_removed_at = NOW() WHERE id = ?", message);
        assertThat(row(alice, chat.id()).get("lastReaction")).isNull();
        jdbc.update("UPDATE messages SET admin_removed_at = NULL WHERE id = ?", message);
        assertThat(row(alice, chat.id()).get("lastReaction")).isNotNull();

        jdbc.update(
                "UPDATE message_reactions SET admin_removed_at = NOW() WHERE message_id = ?",
                message);
        assertThat(row(alice, chat.id()).get("lastReaction")).isNull();
        jdbc.update(
                "UPDATE message_reactions SET admin_removed_at = NULL WHERE message_id = ?",
                message);

        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                alice.id(),
                chat.other().id());
        assertThat(row(alice, chat.id()).get("lastReaction")).isNull();
    }

    private Chat openAcceptedChat(TestUser alice, TestUser other) {
        ResponseEntity<Map> created =
                rest.exchange(
                        "/api/v1/conversations",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("targetUserId", other.id()), authHeaders(alice)),
                        Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) ((Map<?, ?>) created.getBody().get("data")).get("id"));
        jdbc.update(
                "UPDATE conversations SET request_status = 'accepted', request_hidden = FALSE,"
                        + " request_recipient_id = NULL WHERE id = ?",
                id);
        return new Chat(id, alice, other);
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

    // Message timestamps come from the application clock and reaction timestamps from the
    // database clock, so the test moves its messages well into the past instead of relying on the
    // two clocks agreeing to the millisecond.
    private void ageMessage(UUID messageId) {
        jdbc.update(
                "UPDATE messages SET created_at = created_at - interval '1 hour' WHERE id = ?",
                messageId);
        // The list read compares reactions with the conversation's last_message_at, which the
        // insert trigger stamped from the application clock, so it moves with the message.
        jdbc.update(
                "UPDATE conversations c SET last_message_at = (SELECT MAX(created_at) FROM messages"
                        + " WHERE conversation_id = c.id) WHERE c.id = (SELECT conversation_id FROM"
                        + " messages WHERE id = ?)",
                messageId);
    }

    private ResponseEntity<Map> put(
            TestUser actor, UUID conversationId, UUID messageId, String emoji) {
        return rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages/" + messageId + "/reaction",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("emoji", emoji), authHeaders(actor)),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(TestUser viewer) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations?limit=50",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(viewer)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>)
                ((Map<?, ?>) response.getBody().get("data")).get("content");
    }

    private List<String> conversationIds(TestUser viewer) {
        return list(viewer).stream().map(r -> (String) r.get("id")).toList();
    }

    private Map<String, Object> row(TestUser viewer, UUID conversationId) {
        return list(viewer).stream()
                .filter(r -> conversationId.toString().equals(r.get("id")))
                .findFirst()
                .orElseThrow();
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
