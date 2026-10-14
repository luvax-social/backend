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
class MessageReplyPreviewIT {

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
        registry.add("JWT_SECRET", () -> "message-reply-preview-it-secret-32-chars!!!!!");
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
    void history_parentTwoHundredMessagesBack_carriesPreviewOnFirstPage() {
        Chat thread = openThread("reply_far");
        UUID parent = sendId(thread.alice(), thread.id(), "the original question", null);
        jdbc.update(
                """
				INSERT INTO messages(conversation_id, sender_id, message_type, content, created_at)
				SELECT ?, ?, 'text', 'filler ' || i,
					(SELECT created_at FROM messages WHERE id = ?) + i * interval '1 microsecond'
				FROM generate_series(1, 200) AS i
				""",
                thread.id(),
                thread.bob().id(),
                parent);
        sendId(thread.alice(), thread.id(), "answering that", parent);

        Map<String, Object> first = history(thread.alice(), thread.id(), 20).get(0);

        Map<?, ?> replyTo = (Map<?, ?>) first.get("replyTo");
        assertThat(replyTo.get("messageId")).isEqualTo(parent.toString());
        assertThat(replyTo.get("senderId")).isEqualTo(thread.alice().id().toString());
        assertThat(replyTo.get("messageType")).isEqualTo("text");
        assertThat(replyTo.get("snippet")).isEqualTo("the original question");
        assertThat(replyTo.get("unavailable")).isEqualTo(false);
    }

    @Test
    void history_senderDeletedParent_isUnavailableWithNoSnippet() {
        Chat thread = openThread("reply_deleted");
        UUID parent = sendId(thread.alice(), thread.id(), "regretted words", null);
        sendId(thread.bob(), thread.id(), "a reply to it", parent);
        ResponseEntity<Map> deleted =
                rest.exchange(
                        "/api/v1/conversations/" + thread.id() + "/messages/" + parent,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(thread.alice())),
                        Map.class);
        assertThat(deleted.getStatusCode().is2xxSuccessful()).isTrue();

        Map<?, ?> replyTo =
                (Map<?, ?>) history(thread.bob(), thread.id(), 20).get(0).get("replyTo");

        assertThat(replyTo.get("unavailable")).isEqualTo(true);
        assertThat(replyTo.get("snippet")).isNull();
        assertThat(replyTo.get("senderId")).isNull();
        assertThat(replyTo.get("messageType")).isNull();
    }

    @Test
    void history_adminRemovedParent_isUnavailableUntilRestored() {
        Chat thread = openThread("reply_removed");
        UUID parent = sendId(thread.alice(), thread.id(), "reported words", null);
        sendId(thread.bob(), thread.id(), "a reply to it", parent);
        jdbc.update("UPDATE messages SET admin_removed_at = NOW() WHERE id = ?", parent);

        Map<?, ?> removed =
                (Map<?, ?>) history(thread.bob(), thread.id(), 20).get(0).get("replyTo");
        assertThat(removed.get("unavailable")).isEqualTo(true);
        assertThat(removed.get("snippet")).isNull();
        assertThat(removed.get("senderId")).isNull();
        assertThat(removed.get("messageType")).isNull();
        assertThat(removed.get("messageId")).isEqualTo(parent.toString());

        jdbc.update("UPDATE messages SET admin_removed_at = NULL WHERE id = ?", parent);

        Map<?, ?> restored =
                (Map<?, ?>) history(thread.bob(), thread.id(), 20).get(0).get("replyTo");
        assertThat(restored.get("unavailable")).isEqualTo(false);
        assertThat(restored.get("snippet")).isEqualTo("reported words");
    }

    @Test
    void history_mediaParent_givesTypeAndNoSnippet() {
        Chat thread = openThread("reply_media");
        UUID parent =
                jdbc.queryForObject(
                        """
						INSERT INTO messages(conversation_id, sender_id, message_type, created_at)
						VALUES (?, ?, 'image', NOW() - interval '1 minute') RETURNING id
						""",
                        UUID.class,
                        thread.id(),
                        thread.alice().id());
        sendId(thread.bob(), thread.id(), "nice picture", parent);

        Map<?, ?> replyTo =
                (Map<?, ?>) history(thread.bob(), thread.id(), 20).get(0).get("replyTo");

        assertThat(replyTo.get("messageType")).isEqualTo("image");
        assertThat(replyTo.get("snippet")).isNull();
        assertThat(replyTo.get("unavailable")).isEqualTo(false);
    }

    @Test
    void send_replyResponseAndLiveEvent_carryTheSamePreview() {
        Chat thread = openThread("reply_live");
        UUID parent = sendId(thread.alice(), thread.id(), "live parent text", null);

        ResponseEntity<Map> response = send(thread.bob(), thread.id(), "live child", parent);

        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        Map<?, ?> replyTo = (Map<?, ?>) data.get("replyTo");
        assertThat(replyTo.get("snippet")).isEqualTo("live parent text");
        String payload =
                jdbc.queryForObject(
                        "SELECT payload::text FROM outbox_events WHERE event_type ="
                                + " 'message.sent.v1' AND aggregate_id = ?",
                        String.class,
                        UUID.fromString((String) data.get("id")));
        assertThat(payload).contains("\"replyTo\"").contains("live parent text");
    }

    @Test
    void history_messageWithoutReply_hasNoPreview() {
        Chat thread = openThread("reply_none");
        sendId(thread.alice(), thread.id(), "standalone", null);

        assertThat(history(thread.alice(), thread.id(), 20).get(0).get("replyTo")).isNull();
    }

    private Chat openThread(String prefix) {
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
        // A conversation between strangers starts as a request; opening it up here lets each test
        // send freely from both sides.
        jdbc.update(
                "UPDATE conversations SET request_status = 'accepted', request_hidden = FALSE,"
                        + " request_recipient_id = NULL WHERE id = ?",
                id);
        return new Chat(id, alice, bob);
    }

    private UUID sendId(TestUser actor, UUID conversationId, String content, UUID replyToId) {
        ResponseEntity<Map> response = send(actor, conversationId, content, replyToId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) ((Map<?, ?>) response.getBody().get("data")).get("id"));
    }

    private ResponseEntity<Map> send(
            TestUser actor, UUID conversationId, String content, UUID replyToId) {
        Map<String, Object> body = new HashMap<>();
        body.put("messageType", "text");
        body.put("content", content);
        body.put("replyToId", replyToId);
        return rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, authHeaders(actor)),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history(TestUser actor, UUID conversationId, int limit) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/messages?limit=" + limit,
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(actor)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>)
                ((Map<?, ?>) response.getBody().get("data")).get("content");
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
