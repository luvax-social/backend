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
class PendingRequestSendRulesIT {

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
        registry.add("JWT_SECRET", () -> "message-sendrules-it-secret-32-chars!!!!!!");
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
    void pendingRequester_firstTextSucceeds_secondRefusedAwaitingAcceptance() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        UUID conversationId = createDirectOk(alice, bob.id());

        ResponseEntity<Map> first = send(alice, conversationId, "hello bob");
        ResponseEntity<Map> second = send(alice, conversationId, "are you there");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("MESSAGE_REQUEST_AWAITING_ACCEPTANCE");
        assertThat(messageCount(conversationId)).isEqualTo(1);
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("pending");
    }

    @Test
    void pendingRequester_firstImageRefused() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        UUID conversationId = createDirectOk(alice, bob.id());

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/messages",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("messageType", "image", "mediaAssetId", UUID.randomUUID()),
                                authHeaders(alice)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_REQUEST_AWAITING_ACCEPTANCE");
        assertThat(messageCount(conversationId)).isZero();
        assertThat(send(alice, conversationId, "a text still goes through").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void pendingRequester_firstStoryReplySucceeds_secondMessageRefused() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        UUID conversationId = createDirectOk(alice, bob.id());
        UUID storyId = insertStory(bob.id());

        ResponseEntity<Map> reply = sendStoryReply(alice, conversationId, storyId, "what a view");
        ResponseEntity<Map> second = send(alice, conversationId, "and one more");

        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dataOf(reply).get("messageType")).isEqualTo("story_share");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo("MESSAGE_REQUEST_AWAITING_ACCEPTANCE");
        assertThat(messageCount(conversationId)).isEqualTo(1);
        assertThat(folderIds(bob, "requests")).containsExactly(conversationId);
    }

    @Test
    void pendingRequester_firstStoryReplyAfterAText_refused() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        UUID conversationId = createDirectOk(alice, bob.id());
        UUID storyId = insertStory(bob.id());
        assertThat(send(alice, conversationId, "hello bob").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> reply = sendStoryReply(alice, conversationId, storyId, "what a view");

        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reply.getBody().get("code")).isEqualTo("MESSAGE_REQUEST_AWAITING_ACCEPTANCE");
        assertThat(messageCount(conversationId)).isEqualTo(1);
    }

    @Test
    void pendingRequester_storyReplyMatchingRecipientList_marksRequestHidden() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        jdbc.update(
                "INSERT INTO user_hidden_words(user_id, phrase, normalized_phrase)"
                        + " VALUES (?, 'zorblax', 'zorblax')",
                bob.id());
        UUID conversationId = createDirectOk(alice, bob.id());
        UUID storyId = insertStory(bob.id());

        ResponseEntity<Map> reply =
                sendStoryReply(alice, conversationId, storyId, "you are a Zorblax!");

        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(conversationRow(conversationId).get("request_hidden")).isEqualTo(true);
        assertThat(folderIds(bob, "hidden_requests")).containsExactly(conversationId);
    }

    @Test
    void pendingRequester_firstTextMatchingRecipientList_marksRequestHidden() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        jdbc.update(
                "INSERT INTO user_hidden_words(user_id, phrase, normalized_phrase)"
                        + " VALUES (?, 'zorblax', 'zorblax')",
                bob.id());
        UUID conversationId = createDirectOk(alice, bob.id());
        assertThat(conversationRow(conversationId).get("request_hidden")).isEqualTo(false);

        ResponseEntity<Map> response = send(alice, conversationId, "you are a Zorblax!");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(conversationRow(conversationId).get("request_hidden")).isEqualTo(true);
        assertThat(folderIds(bob, "hidden_requests")).containsExactly(conversationId);
        assertThat(folderIds(bob, "requests")).isEmpty();
    }

    @Test
    void pendingRecipient_sendingAcceptsRequest() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        UUID conversationId = createDirectOk(alice, bob.id());
        assertThat(send(alice, conversationId, "hello bob").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> reply = send(bob, conversationId, "hello alice");

        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("accepted");
        assertThat(conversationRow(conversationId).get("request_hidden")).isEqualTo(false);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT request_resolved_at IS NOT NULL FROM conversations WHERE id = ?",
                                Boolean.class,
                                conversationId))
                .isTrue();
        assertThat(send(alice, conversationId, "now I can keep talking").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(folderIds(bob, "primary")).contains(conversationId);
    }

    @Test
    void declinedRequester_refusedExactlyLikePending() {
        TestUser alice = registerUser("sr_a");
        TestUser pendingRecipient = registerUser("sr_p");
        TestUser declinedRecipient = registerUser("sr_d");
        TestUser untouchedRecipient = registerUser("sr_u");
        UUID pending = createDirectOk(alice, pendingRecipient.id());
        UUID declinedAfterMessage = createDirectOk(alice, declinedRecipient.id());
        UUID declinedBeforeMessage = createDirectOk(alice, untouchedRecipient.id());
        assertThat(send(alice, pending, "first").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(alice, declinedAfterMessage, "first").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        jdbc.update(
                "UPDATE conversations SET request_status = 'declined', request_resolved_at = NOW()"
                        + " WHERE id IN (?, ?)",
                declinedAfterMessage,
                declinedBeforeMessage);

        ResponseEntity<Map> fromPending = send(alice, pending, "second");
        ResponseEntity<Map> fromDeclined = send(alice, declinedAfterMessage, "second");
        ResponseEntity<Map> fromDeclinedBeforeMessage = send(alice, declinedBeforeMessage, "first");

        for (ResponseEntity<Map> refused :
                List.of(fromPending, fromDeclined, fromDeclinedBeforeMessage)) {
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("code"))
                    .isEqualTo("MESSAGE_REQUEST_AWAITING_ACCEPTANCE");
            assertThat(refused.getBody().get("message"))
                    .isEqualTo(fromPending.getBody().get("message"));
        }
        assertThat(messageCount(declinedAfterMessage)).isEqualTo(1);
        assertThat(messageCount(declinedBeforeMessage)).isZero();
    }

    @Test
    void pendingRequester_retryOfTheFirstMessageWithTheSameKey_replaysItInsteadOfRefusing() {
        TestUser alice = registerUser("sr_a");
        TestUser bob = registerUser("sr_b");
        UUID conversationId = createDirectOk(alice, bob.id());
        HttpHeaders headers = authHeaders(alice);
        headers.add("Idempotency-Key", "first-message-key");
        Map<String, Object> body = Map.of("messageType", "text", "content", "hello bob");

        ResponseEntity<Map> first =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/messages",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);
        ResponseEntity<Map> retry =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/messages",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(dataOf(retry).get("id")).isEqualTo(dataOf(first).get("id"));
        assertThat(messageCount(conversationId)).isEqualTo(1);
    }

    private int messageCount(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                Integer.class,
                conversationId);
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

    private UUID insertStory(UUID ownerId) {
        UUID mediaAssetId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size) VALUES (?, ?, ?, ?, CAST('image' AS media_type),"
                        + " 'image/jpeg', 1024)",
                mediaAssetId,
                ownerId,
                "test/" + mediaAssetId,
                "https://cdn.test/" + mediaAssetId);
        UUID storyId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO stories (id, user_id, media_asset_id, story_type)"
                        + " VALUES (?, ?, ?, CAST('image' AS story_type))",
                storyId,
                ownerId,
                mediaAssetId);
        return storyId;
    }

    private ResponseEntity<Map> sendStoryReply(
            TestUser actor, UUID conversationId, UUID storyId, String content) {
        return rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "messageType", "story_share",
                                "content", content,
                                "sharedStoryId", storyId),
                        authHeaders(actor)),
                Map.class);
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
