package com.app.modules.message.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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
class MessageControllerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        // These tests drive login, register and report submission as setup, not as the
        // subject under test. The kill switch keeps them off the network: the dev profile
        // defaults the secret to Cloudflare's test key, and a real siteverify call would
        // make the suite depend on an external service being reachable.
        r.add("TURNSTILE_AUTH_ENABLED", () -> false);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "message-controller-it-secret-32-chars-min!!!!");
        r.add("JWT_ISSUER", () -> "https://message.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbcTemplate;

    private record TestUser(UUID id, String token) {}

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM messages");
        jdbcTemplate.update("DELETE FROM conversation_participants");
        jdbcTemplate.update("DELETE FROM conversations");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM oauth_accounts");
        jdbcTemplate.update("DELETE FROM user_credentials");
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void createDirectConversation_success_returnsConversationWithBothParticipants() {
        TestUser alice = registerUser("dm_alice");
        TestUser bob = registerUser("dm_bob");

        ResponseEntity<Map> response = createDirect(alice, bob.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        List<Map<?, ?>> participants = (List<Map<?, ?>>) data.get("participants");
        assertThat(participants).hasSize(2);
        assertThat(activeParticipantIds(UUID.fromString((String) data.get("id"))))
                .containsExactlyInAnyOrder(alice.id(), bob.id());
    }

    @Test
    void createDirectConversation_calledTwice_reusesSameConversation() {
        TestUser alice = registerUser("dedup_alice");
        TestUser bob = registerUser("dedup_bob");

        ResponseEntity<Map> first = createDirect(alice, bob.id());
        ResponseEntity<Map> second = createDirect(bob, alice.id());

        String firstId = (String) ((Map<?, ?>) first.getBody().get("data")).get("id");
        String secondId = (String) ((Map<?, ?>) second.getBody().get("data")).get("id");
        assertThat(secondId).isEqualTo(firstId);
        assertThat(conversationCount()).isEqualTo(1);
    }

    @Test
    void createDirectConversation_concurrentCallsForSamePair_createExactlyOneConversation()
            throws Exception {
        TestUser alice = registerUser("race_alice");
        TestUser bob = registerUser("race_bob");
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                TestUser caller = (i % 2 == 0) ? alice : bob;
                UUID target = (i % 2 == 0) ? bob.id() : alice.id();
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return createDirect(caller, target);
                                }));
            }
            ready.await();
            start.countDown();

            Set<String> conversationIds = new HashSet<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                ResponseEntity<Map> response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                conversationIds.add(conversationIdOf(response).toString());
            }

            assertThat(conversationIds).hasSize(1);
            assertThat(conversationCount()).isEqualTo(1);
            assertThat(activeParticipantIds(UUID.fromString(conversationIds.iterator().next())))
                    .containsExactlyInAnyOrder(alice.id(), bob.id());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createDirectConversation_blockedTarget_returnsNotFound() {
        // Stealth block model: a blocked target must be indistinguishable from a nonexistent one,
        // so this is NOT_FOUND rather than a status that confirms the block relationship exists.
        TestUser alice = registerUser("block_alice");
        TestUser bob = registerUser("block_bob");
        jdbcTemplate.update(
                "INSERT INTO blocks (blocker_id, blocked_id) VALUES (?, ?)", bob.id(), alice.id());

        ResponseEntity<Map> response = createDirect(alice, bob.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("NOT_FOUND");
    }

    @Test
    void createDirectConversation_targetNotFound_returnsNotFound() {
        TestUser alice = registerUser("missing_target_alice");

        ResponseEntity<Map> response = createDirect(alice, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("USER_NOT_FOUND");
    }

    @Test
    void createDirectConversation_requestsDisabledAndNotFollowedBack_returnsForbidden() {
        TestUser alice = registerUser("req_alice");
        TestUser bob = registerUser("req_bob");
        jdbcTemplate.update(
                "UPDATE user_settings SET allow_message_requests = FALSE WHERE user_id = ?",
                bob.id());

        ResponseEntity<Map> response = createDirect(alice, bob.id());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_REQUEST_NOT_ALLOWED");
    }

    @Test
    void listMyConversations_returnsCreatedConversation() {
        TestUser alice = registerUser("list_alice");
        TestUser bob = registerUser("list_bob");
        createDirect(alice, bob.id());

        ResponseEntity<Map> response = getWithAuth("/api/v1/conversations", alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        List<Map<?, ?>> content = (List<Map<?, ?>>) data.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("unreadCount")).isEqualTo(0);
    }

    @Test
    void listMyConversations_cursorAcrossNullLastMessageAtRows_paginatesWithoutError() {
        TestUser alice = registerUser("nullcursor_alice");
        TestUser bob = registerUser("nullcursor_bob");
        TestUser carol = registerUser("nullcursor_carol");
        UUID conv1 = conversationIdOf(createDirect(alice, bob.id()));
        UUID conv2 = conversationIdOf(createDirect(alice, carol.id()));

        ResponseEntity<Map> firstPage = getWithAuth("/api/v1/conversations?limit=1", alice);

        assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> firstData = (Map<?, ?>) firstPage.getBody().get("data");
        List<Map<?, ?>> firstContent = (List<Map<?, ?>>) firstData.get("content");
        assertThat(firstContent).hasSize(1);
        Map<?, ?> firstPageInfo = (Map<?, ?>) firstData.get("pageInfo");
        String endCursor = (String) firstPageInfo.get("endCursor");
        assertThat(endCursor).isNotBlank();

        ResponseEntity<Map> secondPage =
                getWithAuth("/api/v1/conversations?limit=1&cursor=" + endCursor, alice);

        assertThat(secondPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> secondData = (Map<?, ?>) secondPage.getBody().get("data");
        List<Map<?, ?>> secondContent = (List<Map<?, ?>>) secondData.get("content");
        assertThat(secondContent).hasSize(1);
        Set<String> seenIds =
                Set.of(
                        (String) firstContent.get(0).get("id"),
                        (String) secondContent.get(0).get("id"));
        assertThat(seenIds).containsExactlyInAnyOrder(conv1.toString(), conv2.toString());
    }

    @Test
    void listMyConversations_extremeCursorValue_neverReturns500() {
        TestUser alice = registerUser("forged_cursor_alice");

        // A hand-crafted cursor carrying Long.MAX_VALUE microseconds, the most extreme value the
        // scoped codec's long sort field can carry, the same bound the other 17
        // CursorCodec-backed endpoints already rely on. Under the previous free-text
        // OffsetDateTime.parse cursor, a comparably extreme year overflowed at JDBC bind time
        // ("date/time field value out of range", 500). Bounding the value to a long keeps the
        // decoded instant within PostgreSQL's representable range, so this resolves to a clean,
        // empty page instead - never a 500.
        String extreme =
                com.app.common.pagination.CursorCodec.encode(
                        new com.app.common.pagination.Cursor(
                                Long.MAX_VALUE, java.util.UUID.randomUUID()),
                        com.app.common.pagination.CursorScope.CONVERSATIONS);

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/conversations?cursor=" + extreme, alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat((List<?>) data.get("content")).isEmpty();
    }

    @Test
    void listMyConversations_garbledCursor_returns400NotServerError() {
        TestUser alice = registerUser("garbled_cursor_alice");

        String garbled =
                java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "garbage-not-a-real-cursor-payload"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/conversations?cursor=" + garbled, alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void listMyConversations_limitZero_returns400() {
        TestUser alice = registerUser("conversations_limitzero_alice");

        ResponseEntity<Map> response = getWithAuth("/api/v1/conversations?limit=0", alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void getConversation_nonParticipant_returnsForbidden() {
        TestUser alice = registerUser("detail_alice");
        TestUser bob = registerUser("detail_bob");
        TestUser stranger = registerUser("detail_stranger");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/conversations/" + conversationId, stranger);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("CONVERSATION_FORBIDDEN");
    }

    @Test
    void getConversation_notFound_returnsNotFound() {
        TestUser alice = registerUser("notfound_alice");

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/conversations/" + UUID.randomUUID(), alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    void sendMessage_text_success_appearsInHistory() {
        TestUser alice = registerUser("send_text_alice");
        TestUser bob = registerUser("send_text_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));

        ResponseEntity<Map> response =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "text", "content", "hello bob"),
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("content")).isEqualTo("hello bob");
        assertThat(data.get("senderId")).isEqualTo(alice.id().toString());

        ResponseEntity<Map> history =
                getWithAuth("/api/v1/conversations/" + conversationId + "/messages", bob);
        List<Map<?, ?>> content = historyContent(history);
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("content")).isEqualTo("hello bob");
    }

    @Test
    void sendMessage_image_success_referencesMediaAsset() {
        TestUser alice = registerUser("send_image_alice");
        TestUser bob = registerUser("send_image_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID mediaAssetId = insertMediaAsset(alice.id(), "image");

        ResponseEntity<Map> response =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "image", "mediaAssetId", mediaAssetId.toString()),
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("mediaAssetId")).isEqualTo(mediaAssetId.toString());
    }

    @Test
    void sendMessage_image_responseCarriesResolvableMedia() {
        // A recipient otherwise holds an opaque asset id and cannot render the attachment: the
        // media module publishes upload, upload-complete, and constraints, and no lookup by id.
        TestUser alice = registerUser("media_embed_alice");
        TestUser bob = registerUser("media_embed_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID mediaAssetId = insertMediaAsset(alice.id(), "image");

        ResponseEntity<Map> response =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "image", "mediaAssetId", mediaAssetId.toString()),
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        Map<?, ?> media = (Map<?, ?>) data.get("media");
        assertThat(media).isNotNull();
        assertThat(media.get("mediaAssetId")).isEqualTo(mediaAssetId.toString());
        assertThat((String) media.get("cdnUrl")).isNotBlank();
    }

    @Test
    void listHistory_imageMessage_carriesMediaAndTextMessageDoesNot() {
        TestUser alice = registerUser("media_hist_alice");
        TestUser bob = registerUser("media_hist_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID mediaAssetId = insertMediaAsset(alice.id(), "image");
        sendMessage(alice, conversationId, Map.of("messageType", "text", "content", "plain"), null);
        sendMessage(
                alice,
                conversationId,
                Map.of("messageType", "image", "mediaAssetId", mediaAssetId.toString()),
                null);

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/conversations/" + conversationId + "/messages", bob);

        List<Map<?, ?>> content = historyContent(response);
        Map<?, ?> imageMessage =
                content.stream()
                        .filter(m -> mediaAssetId.toString().equals(m.get("mediaAssetId")))
                        .findFirst()
                        .orElseThrow();
        Map<?, ?> textMessage =
                content.stream()
                        .filter(m -> "plain".equals(m.get("content")))
                        .findFirst()
                        .orElseThrow();

        assertThat((Map<?, ?>) imageMessage.get("media")).isNotNull();
        // The field must be conditional, not an empty object on every row.
        assertThat(textMessage.get("media")).isNull();
    }

    @Test
    void sendMessage_postShare_success_referencesPost() {
        TestUser alice = registerUser("send_post_alice");
        TestUser bob = registerUser("send_post_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID postId = insertPost(alice.id());

        ResponseEntity<Map> response =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "post_share", "sharedPostId", postId.toString()),
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("sharedPostId")).isEqualTo(postId.toString());
    }

    @Test
    void sendMessage_storyShare_success_referencesStory() {
        TestUser alice = registerUser("send_story_alice");
        TestUser bob = registerUser("send_story_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID mediaAssetId = insertMediaAsset(alice.id(), "image");
        UUID storyId = insertStory(alice.id(), mediaAssetId);

        ResponseEntity<Map> response =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "story_share", "sharedStoryId", storyId.toString()),
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("sharedStoryId")).isEqualTo(storyId.toString());
    }

    @Test
    void sendMessage_image_withAnotherUsersMediaAsset_returnsBadRequest() {
        TestUser alice = registerUser("send_image_owner_alice");
        TestUser bob = registerUser("send_image_spoof_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID aliceMediaAssetId = insertMediaAsset(alice.id(), "image");

        ResponseEntity<Map> response =
                sendMessage(
                        bob,
                        conversationId,
                        Map.of(
                                "messageType",
                                "image",
                                "mediaAssetId",
                                aliceMediaAssetId.toString()),
                        null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_INVALID_PAYLOAD");
    }

    @Test
    void sendMessage_postShare_withDraftPost_returnsBadRequest() {
        TestUser alice = registerUser("send_draft_post_alice");
        TestUser bob = registerUser("send_draft_post_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID postId = insertPostWithStatus(alice.id(), "draft");

        ResponseEntity<Map> response =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "post_share", "sharedPostId", postId.toString()),
                        null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_INVALID_PAYLOAD");
    }

    @Test
    void sendMessage_storyShare_withExpiredStory_returnsBadRequest() {
        TestUser alice = registerUser("send_expired_story_alice");
        TestUser bob = registerUser("send_expired_story_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        UUID mediaAssetId = insertMediaAsset(alice.id(), "image");
        UUID storyId = insertStory(alice.id(), mediaAssetId);
        jdbcTemplate.update(
                "UPDATE stories SET expires_at = NOW() - INTERVAL '1 hour' WHERE id = ?", storyId);

        ResponseEntity<Map> response =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "story_share", "sharedStoryId", storyId.toString()),
                        null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_INVALID_PAYLOAD");
    }

    @Test
    void sendMessage_textWithoutContent_returnsBadRequest() {
        TestUser alice = registerUser("send_invalid_alice");
        TestUser bob = registerUser("send_invalid_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));

        ResponseEntity<Map> response =
                sendMessage(alice, conversationId, Map.of("messageType", "text"), null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_INVALID_PAYLOAD");
    }

    @Test
    void sendMessage_nonParticipant_returnsForbidden() {
        TestUser alice = registerUser("send_forbidden_alice");
        TestUser bob = registerUser("send_forbidden_bob");
        TestUser stranger = registerUser("send_forbidden_stranger");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));

        ResponseEntity<Map> response =
                sendMessage(
                        stranger,
                        conversationId,
                        Map.of("messageType", "text", "content", "hi"),
                        null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("CONVERSATION_FORBIDDEN");
    }

    @Test
    void sendMessage_idempotencyKeyReplay_returnsSameMessageSingleRow() {
        TestUser alice = registerUser("idem_alice");
        TestUser bob = registerUser("idem_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        Map<String, Object> body = Map.of("messageType", "text", "content", "idempotent hello");

        ResponseEntity<Map> first = sendMessage(alice, conversationId, body, "msg-key-1");
        ResponseEntity<Map> second = sendMessage(alice, conversationId, body, "msg-key-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstId = (String) ((Map<?, ?>) first.getBody().get("data")).get("id");
        String secondId = (String) ((Map<?, ?>) second.getBody().get("data")).get("id");
        assertThat(secondId).isEqualTo(firstId);
        assertThat(messageCount(conversationId)).isEqualTo(1);
    }

    @Test
    void listHistory_returnsMessagesNewestFirstIncludingTombstone() {
        TestUser alice = registerUser("history_alice");
        TestUser bob = registerUser("history_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        sendMessage(alice, conversationId, Map.of("messageType", "text", "content", "first"), null);
        ResponseEntity<Map> secondSend =
                sendMessage(
                        bob,
                        conversationId,
                        Map.of("messageType", "text", "content", "second"),
                        null);
        UUID secondMessageId =
                UUID.fromString((String) ((Map<?, ?>) secondSend.getBody().get("data")).get("id"));
        rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages/" + secondMessageId,
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(bob)),
                Map.class);

        ResponseEntity<Map> response =
                getWithAuth("/api/v1/conversations/" + conversationId + "/messages", alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> content = historyContent(response);
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("id")).isEqualTo(secondMessageId.toString());
        assertThat(content.get(0).get("isDeleted")).isEqualTo(true);
        assertThat(content.get(0).get("content")).isNull();
        assertThat(content.get(1).get("content")).isEqualTo("first");
    }

    @Test
    void deleteMessage_byNonSender_returnsForbidden() {
        TestUser alice = registerUser("delnonsender_alice");
        TestUser bob = registerUser("delnonsender_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        ResponseEntity<Map> sent =
                sendMessage(
                        alice,
                        conversationId,
                        Map.of("messageType", "text", "content", "mine"),
                        null);
        UUID messageId =
                UUID.fromString((String) ((Map<?, ?>) sent.getBody().get("data")).get("id"));

        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/messages/" + messageId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(bob)),
                        Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo("MESSAGE_FORBIDDEN");
    }

    @Test
    void markRead_resetsUnreadCountToZero() {
        TestUser alice = registerUser("read_alice");
        TestUser bob = registerUser("read_bob");
        UUID conversationId = conversationIdOf(createDirect(alice, bob.id()));
        sendMessage(
                alice,
                conversationId,
                Map.of("messageType", "text", "content", "unread for bob"),
                null);

        ResponseEntity<Map> beforeRead = getWithAuth("/api/v1/conversations/unread-count", bob);
        assertThat(((Map<?, ?>) beforeRead.getBody().get("data")).get("unreadCount")).isEqualTo(1);

        ResponseEntity<Map> markReadResponse =
                rest.exchange(
                        "/api/v1/conversations/" + conversationId + "/read",
                        HttpMethod.POST,
                        new HttpEntity<>(authHeaders(bob)),
                        Map.class);
        assertThat(markReadResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> afterRead = getWithAuth("/api/v1/conversations/unread-count", bob);
        assertThat(((Map<?, ?>) afterRead.getBody().get("data")).get("unreadCount")).isEqualTo(0);
    }

    private ResponseEntity<Map> sendMessage(
            TestUser actor, UUID conversationId, Map<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = authHeaders(actor);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> historyContent(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return (List<Map<?, ?>>) data.get("content");
    }

    private int messageCount(UUID conversationId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                Integer.class,
                conversationId);
    }

    private UUID insertMediaAsset(UUID ownerId, String mediaType) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, user_id, storage_key, cdn_url, media_type,"
                        + " mime_type, file_size) VALUES (?, ?, ?, ?, CAST(? AS media_type), ?, ?)",
                id,
                ownerId,
                "test/" + id,
                "https://cdn.test/" + id,
                mediaType,
                "image".equals(mediaType) ? "image/jpeg" : "video/mp4",
                1024L);
        return id;
    }

    private UUID insertPost(UUID ownerId) {
        return insertPostWithStatus(ownerId, "published");
    }

    private UUID insertPostWithStatus(UUID ownerId, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO posts (id, user_id, caption, post_type, status) "
                        + "VALUES (?, ?, 'Shared post', 'text', CAST(? AS post_status))",
                id,
                ownerId,
                status);
        return id;
    }

    private UUID insertStory(UUID ownerId, UUID mediaAssetId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO stories (id, user_id, media_asset_id, story_type) "
                        + "VALUES (?, ?, ?, CAST(? AS story_type))",
                id,
                ownerId,
                mediaAssetId,
                "image");
        return id;
    }

    private ResponseEntity<Map> createDirect(TestUser actor, UUID targetId) {
        return rest.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("targetUserId", targetId.toString()), authHeaders(actor)),
                Map.class);
    }

    private static UUID conversationIdOf(ResponseEntity<Map> response) {
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        return UUID.fromString((String) data.get("id"));
    }

    private int conversationCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class);
    }

    private List<UUID> activeParticipantIds(UUID conversationId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id FROM conversation_participants"
                        + " WHERE conversation_id = ? AND left_at IS NULL",
                UUID.class,
                conversationId);
    }

    private boolean isAdmin(UUID conversationId, UUID userId) {
        Boolean admin =
                jdbcTemplate.queryForObject(
                        "SELECT is_admin FROM conversation_participants"
                                + " WHERE conversation_id = ? AND user_id = ?",
                        Boolean.class,
                        conversationId,
                        userId);
        return Boolean.TRUE.equals(admin);
    }

    private TestUser registerUser(String username) {
        String email = username + "@test.local";
        String password = "S3cur3P@ssword!";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", uniqueIp());

        Map<String, Object> registerPayload =
                Map.of(
                        "username", username,
                        "email", email,
                        "password", password,
                        "displayName", username);
        ResponseEntity<Map> reg =
                rest.exchange(
                        "/api/v1/auth/register",
                        HttpMethod.POST,
                        new HttpEntity<>(registerPayload, headers),
                        Map.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID id =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM users WHERE username = ?", UUID.class, username);
        jdbcTemplate.update(
                "UPDATE user_credentials SET email_verified = TRUE WHERE user_id = ?", id);

        ResponseEntity<Map> login =
                rest.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                Map.of("identifier", email, "password", password), headers),
                        Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) login.getBody().get("data");
        return new TestUser(id, (String) data.get("accessToken"));
    }

    @Test
    void groupEndpoints_areNoLongerRouted() {
        // The routes must be absent, not merely reject their bodies. The exact status differs by
        // whether the path still matches a surviving route: /conversations/group now falls onto the
        // GET-only /conversations/{id} pattern, and this application maps method-not-supported to
        // 400 rather than 405. What matters is that none of them succeeds.
        TestUser alice = registerUser("gone_alice");
        TestUser bob = registerUser("gone_bob");
        UUID conversationId =
                UUID.fromString(
                        (String)
                                ((Map<?, ?>) createDirect(alice, bob.id()).getBody().get("data"))
                                        .get("id"));

        assertGone(
                "/api/v1/conversations/group",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("groupName", "gone", "participantIds", List.of(bob.id())),
                        authHeaders(alice)));
        assertGone(
                "/api/v1/conversations/" + conversationId + "/participants",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(alice)));
        assertGone(
                "/api/v1/conversations/" + conversationId + "/participants/" + bob.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(alice)));
        assertGone(
                "/api/v1/conversations/" + conversationId + "/leave",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(alice)));
        assertGone(
                "/api/v1/conversations/" + conversationId,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("groupName", "gone"), authHeaders(alice)));
    }

    private void assertGone(String url, HttpMethod method, HttpEntity<?> request) {
        ResponseEntity<Map> response = rest.exchange(url, method, request, Map.class);
        assertThat(response.getStatusCode().is4xxClientError())
                .as("%s %s must not be routed", method, url)
                .isTrue();
        assertThat(response.getBody().get("success")).isEqualTo(false);
    }

    private HttpHeaders authHeaders(TestUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<Map> getWithAuth(String url, TestUser user) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(user)), Map.class);
    }

    private static String uniqueIp() {
        Random rand = new Random();
        return "10." + rand.nextInt(256) + "." + rand.nextInt(256) + "." + (1 + rand.nextInt(254));
    }
}
