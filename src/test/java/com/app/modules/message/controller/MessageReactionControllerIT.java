package com.app.modules.message.controller;

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
class MessageReactionControllerIT {

    private static final String THUMBS_UP = "👍";
    private static final String HEART = "❤️";
    private static final String LAUGH = "😂";

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
        registry.add("JWT_SECRET", () -> "message-reaction-controller-it-secret-32-chars!");
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
    void react_putChangeAndDelete_roundTripsThroughHistory() {
        Chat chat = openAcceptedChat("rx_round");
        UUID message = sendId(chat.bob(), chat.id(), "hello alice");

        ResponseEntity<Map> put = put(chat.alice(), chat.id(), message, THUMBS_UP);

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) put.getBody().get("data");
        assertThat(data.get("userId")).isEqualTo(chat.alice().id().toString());
        assertThat(data.get("emoji")).isEqualTo(THUMBS_UP);
        UUID storedId = storedReactionId(message);
        assertThat(data.get("id")).isEqualTo(storedId.toString());
        assertThat(reactionsOf(chat.bob(), chat.id(), message))
                .extracting(r -> r.get("emoji"))
                .containsExactly(THUMBS_UP);
        assertThat(reactionsOf(chat.bob(), chat.id(), message))
                .extracting(r -> r.get("id"))
                .containsExactly(storedId.toString());
        String firstReactedAt =
                (String) reactionsOf(chat.bob(), chat.id(), message).get(0).get("reactedAt");

        assertThat(put(chat.alice(), chat.id(), message, HEART).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> changed = reactionsOf(chat.bob(), chat.id(), message);
        assertThat(changed).extracting(r -> r.get("emoji")).containsExactly(HEART);
        assertThat(changed).extracting(r -> r.get("id")).containsExactly(storedId.toString());
        assertThat(reactionRows(message)).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reacted_at > ?::timestamptz FROM message_reactions"
                                        + " WHERE message_id = ?",
                                Boolean.class,
                                firstReactedAt,
                                message))
                .isTrue();

        assertThat(delete(chat.alice(), chat.id(), message).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reactionsOf(chat.bob(), chat.id(), message)).isEmpty();
        assertThat(delete(chat.alice(), chat.id(), message).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        put(chat.alice(), chat.id(), message, HEART);
        assertThat(storedReactionId(message)).isNotEqualTo(storedId);
    }

    @Test
    void react_liveEventCarriesTheStoredReactionId() {
        Chat chat = openAcceptedChat("rx_event_id");
        UUID message = sendId(chat.bob(), chat.id(), "which reaction is this");

        put(chat.alice(), chat.id(), message, THUMBS_UP);
        put(chat.alice(), chat.id(), message, HEART);

        UUID storedId = storedReactionId(message);
        List<String> payloads =
                jdbc.queryForList(
                        "SELECT payload::text FROM outbox_events WHERE event_type ="
                                + " 'message.reaction.changed.v1' AND aggregate_id = ? ORDER BY"
                                + " created_at",
                        String.class,
                        message);
        assertThat(payloads).hasSize(2);
        assertThat(payloads).allSatisfy(p -> assertThat(p).contains(storedId.toString()));
        assertThat(payloads).allSatisfy(p -> assertThat(p).contains("reactionId"));
    }

    @Test
    void react_secondUsersReaction_coexistsWithTheFirst() {
        Chat chat = openAcceptedChat("rx_two");
        UUID message = sendId(chat.bob(), chat.id(), "hello both");

        put(chat.alice(), chat.id(), message, THUMBS_UP);
        put(chat.bob(), chat.id(), message, LAUGH);

        assertThat(reactionsOf(chat.alice(), chat.id(), message))
                .extracting(r -> r.get("userId") + ":" + r.get("emoji"))
                .containsExactly(
                        chat.alice().id() + ":" + THUMBS_UP, chat.bob().id() + ":" + LAUGH);
    }

    @Test
    void react_sameEmojiTwiceAndEachChange_publishOneEventPerChange() {
        Chat chat = openAcceptedChat("rx_events");
        UUID message = sendId(chat.bob(), chat.id(), "count the events");

        put(chat.alice(), chat.id(), message, THUMBS_UP);
        put(chat.alice(), chat.id(), message, THUMBS_UP);
        put(chat.alice(), chat.id(), message, HEART);
        delete(chat.alice(), chat.id(), message);
        delete(chat.alice(), chat.id(), message);

        List<String> payloads =
                jdbc.queryForList(
                        "SELECT payload::text FROM outbox_events WHERE event_type ="
                                + " 'message.reaction.changed.v1' AND aggregate_id = ? ORDER BY"
                                + " created_at",
                        String.class,
                        message);
        assertThat(payloads).hasSize(3);
        assertThat(payloads.get(0)).contains(THUMBS_UP);
        assertThat(payloads.get(1)).contains(HEART);
        assertThat(payloads.get(2)).doesNotContain("emoji");
        assertThat(payloads.get(0)).contains("inboxRecipientIds");
    }

    @Test
    void react_invalidEmoji_isRejectedWithTheReactionCode() {
        Chat chat = openAcceptedChat("rx_invalid");
        UUID message = sendId(chat.bob(), chat.id(), "pick an emoji");

        for (String text : List.of("a", "1", THUMBS_UP + THUMBS_UP, "ok", "❤", "")) {
            ResponseEntity<Map> response = put(chat.alice(), chat.id(), message, text);

            assertThat(response.getStatusCode()).as(text).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_REACTION_INVALID_EMOJI");
        }
        ResponseEntity<Map> missing = put(chat.alice(), chat.id(), message, null);
        assertThat(missing.getBody().get("code")).isEqualTo("MESSAGE_REACTION_INVALID_EMOJI");
        assertThat(reactionRows(message)).isZero();
    }

    @Test
    void react_nonParticipant_isForbidden() {
        Chat chat = openAcceptedChat("rx_outsider");
        TestUser carol = registerUser("rx_outsider_carol");
        UUID message = sendId(chat.bob(), chat.id(), "private words");

        assertThat(put(carol, chat.id(), message, THUMBS_UP).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(carol, chat.id(), message).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reactionRows(message)).isZero();
    }

    @Test
    void react_pendingMessageRequest_letsBothParticipantsReact() {
        TestUser alice = registerUser("rx_pending_alice");
        TestUser bob = registerUser("rx_pending_bob");
        UUID conversation = createDirect(alice, bob.id());
        UUID message = sendId(alice, conversation, "may I message you?");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_status::text FROM conversations WHERE id = ?",
                                String.class,
                                conversation))
                .isEqualTo("pending");

        assertThat(put(bob, conversation, message, THUMBS_UP).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put(alice, conversation, message, HEART).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(reactionRows(message)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_status::text FROM conversations WHERE id = ?",
                                String.class,
                                conversation))
                .isEqualTo("pending");
    }

    @Test
    void react_blockedPair_getsTheStealthNotFoundAndSeesNoReactions() {
        Chat chat = openAcceptedChat("rx_block");
        UUID message = sendId(chat.alice(), chat.id(), "before the block");
        put(chat.bob(), chat.id(), message, THUMBS_UP);
        put(chat.alice(), chat.id(), message, HEART);
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                chat.alice().id(),
                chat.bob().id());

        for (TestUser actor : List.of(chat.alice(), chat.bob())) {
            ResponseEntity<Map> react = put(actor, chat.id(), message, LAUGH);
            assertThat(react.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(react.getBody().get("code")).isEqualTo("CONVERSATION_NOT_FOUND");
            ResponseEntity<Map> unreact = delete(actor, chat.id(), message);
            assertThat(unreact.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(unreact.getBody().get("code")).isEqualTo("CONVERSATION_NOT_FOUND");
        }
        // Each side keeps only its own reaction, because the other party's is hidden from them.
        assertThat(reactionsOf(chat.alice(), chat.id(), message))
                .extracting(r -> r.get("userId"))
                .containsExactly(chat.alice().id().toString());
        assertThat(reactionsOf(chat.bob(), chat.id(), message))
                .extracting(r -> r.get("userId"))
                .containsExactly(chat.bob().id().toString());
        assertThat(reactionRows(message)).isEqualTo(2);
    }

    @Test
    void react_unknownMessage_isNotFound() {
        Chat chat = openAcceptedChat("rx_unknown");

        ResponseEntity<Map> response = put(chat.alice(), chat.id(), UUID.randomUUID(), THUMBS_UP);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_NOT_FOUND");
    }

    @Test
    void react_adminRemovedReaction_isHiddenFromHistory() {
        Chat chat = openAcceptedChat("rx_modded");
        UUID message = sendId(chat.bob(), chat.id(), "moderate this reaction");
        put(chat.alice(), chat.id(), message, THUMBS_UP);

        jdbc.update(
                "UPDATE message_reactions SET admin_removed_at = NOW() WHERE message_id = ?",
                message);

        assertThat(reactionsOf(chat.bob(), chat.id(), message)).isEmpty();
        assertThat(reactionsOf(chat.alice(), chat.id(), message)).isEmpty();
    }

    @Test
    void unreact_withoutAReaction_emitsNothingAndARealRemovalEmitsExactlyOne() {
        Chat chat = openAcceptedChat("rx_unreact_events");
        UUID message = sendId(chat.bob(), chat.id(), "remove me");

        assertThat(delete(chat.alice(), chat.id(), message).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(reactionEvents(message)).isZero();

        put(chat.alice(), chat.id(), message, THUMBS_UP);
        assertThat(reactionEvents(message)).isEqualTo(1);
        delete(chat.alice(), chat.id(), message);
        delete(chat.alice(), chat.id(), message);

        assertThat(reactionEvents(message)).isEqualTo(2);
    }

    @Test
    void unreact_reactionAnAdministratorRemoved_emitsNothing() {
        Chat chat = openAcceptedChat("rx_unreact_modded");
        UUID message = sendId(chat.bob(), chat.id(), "moderated reaction");
        put(chat.alice(), chat.id(), message, THUMBS_UP);
        jdbc.update(
                "UPDATE message_reactions SET admin_removed_at = NOW() WHERE message_id = ?",
                message);

        assertThat(delete(chat.alice(), chat.id(), message).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(reactionRows(message)).isZero();
        assertThat(reactionEvents(message)).isEqualTo(1);
    }

    private int reactionEvents(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type ="
                        + " 'message.reaction.changed.v1' AND aggregate_id = ?",
                Integer.class,
                messageId);
    }

    private Chat openAcceptedChat(String prefix) {
        TestUser alice = registerUser(prefix + "_alice");
        TestUser bob = registerUser(prefix + "_bob");
        UUID id = createDirect(alice, bob.id());
        // A conversation between strangers starts as a request; opening it up lets each test send
        // from both sides.
        jdbc.update(
                "UPDATE conversations SET request_status = 'accepted', request_hidden = FALSE,"
                        + " request_recipient_id = NULL WHERE id = ?",
                id);
        return new Chat(id, alice, bob);
    }

    private UUID createDirect(TestUser actor, UUID targetId) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("targetUserId", targetId), authHeaders(actor)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
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
        Map<String, Object> message =
                messages.stream()
                        .filter(m -> messageId.toString().equals(m.get("id")))
                        .findFirst()
                        .orElseThrow();
        return (List<Map<String, Object>>) message.get("reactions");
    }

    private UUID storedReactionId(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT id FROM message_reactions WHERE message_id = ?", UUID.class, messageId);
    }

    private int reactionRows(UUID messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM message_reactions WHERE message_id = ?",
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
