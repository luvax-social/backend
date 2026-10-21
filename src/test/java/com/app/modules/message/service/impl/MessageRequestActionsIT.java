package com.app.modules.message.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.app.modules.message.service.DirectConversationProvisioner;
import com.app.testsupport.TestContainerImages;

/** The recipient of a message request accepting it, deleting it, or deleting it and blocking. */
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
class MessageRequestActionsIT {

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
        registry.add("JWT_SECRET", () -> "message-actions-it-secret-32-chars!!!!!!");
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
    @Autowired private DirectConversationProvisioner provisioner;

    private record TestUser(UUID id, String token) {}

    @Test
    void accept_setsAcceptedAndMovesTheConversationIntoThePrimaryFolder() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        UUID conversationId = pendingRequest(alice, bob);
        assertThat(folderIds(bob, "requests")).containsExactly(conversationId);

        ResponseEntity<Map> response =
                call(HttpMethod.POST, bob, "/api/v1/conversations/" + conversationId + "/accept");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataOf(response).get("requestStatus")).isEqualTo("accepted");
        assertThat(dataOf(response).get("sendRestriction")).isEqualTo("none");
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("accepted");
        assertThat(conversationRow(conversationId).get("resolved")).isEqualTo(true);
        assertThat(folderIds(bob, "primary")).contains(conversationId);
        assertThat(folderIds(bob, "requests")).isEmpty();
        assertThat(send(alice, conversationId, "now I can keep talking").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(resolution(conversationId)).containsExactly("accepted");
    }

    @Test
    void accept_aHiddenRequest_clearsTheHiddenFlag() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        addHiddenWord(bob, "zorblax");
        UUID conversationId = createDirectOk(alice, bob.id());
        send(alice, conversationId, "you are a Zorblax");
        assertThat(folderIds(bob, "hidden_requests")).containsExactly(conversationId);

        ResponseEntity<Map> response =
                call(HttpMethod.POST, bob, "/api/v1/conversations/" + conversationId + "/accept");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conversationRow(conversationId).get("request_hidden")).isEqualTo(false);
        assertThat(folderIds(bob, "hidden_requests")).isEmpty();
        assertThat(folderIds(bob, "primary")).contains(conversationId);
    }

    @Test
    void replyingToARequest_acceptsItAndRecordsTheResolution() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        UUID conversationId = pendingRequest(alice, bob);

        assertThat(send(bob, conversationId, "hello alice").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(conversationRow(conversationId).get("status")).isEqualTo("accepted");
        assertThat(resolution(conversationId)).containsExactly("accepted");
    }

    @Test
    void delete_declinesTheRequestAndTheRecipientLeavesIt() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        UUID conversationId = pendingRequest(alice, bob);

        ResponseEntity<Map> response =
                call(
                        HttpMethod.DELETE,
                        bob,
                        "/api/v1/conversations/" + conversationId + "/request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("declined");
        assertThat(conversationRow(conversationId).get("resolved")).isEqualTo(true);
        assertThat(recipientLeft(conversationId, bob)).isTrue();
        assertThat(folderIds(bob, "requests")).isEmpty();
        assertThat(blockExists(bob, alice)).isFalse();
        assertThat(resolution(conversationId)).containsExactly("deleted");
        assertThat(messageCount(conversationId)).isEqualTo(1);
    }

    @Test
    void delete_aRequestWithNoMessageYet_isNotFoundAndChangesNothing() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        UUID conversationId = createDirectOk(alice, bob.id());

        ResponseEntity<Map> response =
                call(
                        HttpMethod.DELETE,
                        bob,
                        "/api/v1/conversations/" + conversationId + "/request");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo("CONVERSATION_NOT_FOUND");
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("pending");
        assertThat(recipientLeft(conversationId, bob)).isFalse();
        assertThat(resolution(conversationId)).isEmpty();
    }

    @Test
    void block_declinesAndBlocksTheRequesterInOneStep() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        UUID conversationId = pendingRequest(alice, bob);

        ResponseEntity<Map> response =
                call(HttpMethod.POST, bob, "/api/v1/conversations/" + conversationId + "/block");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("declined");
        assertThat(recipientLeft(conversationId, bob)).isTrue();
        assertThat(blockExists(bob, alice)).isTrue();
        assertThat(resolution(conversationId)).containsExactly("blocked");
    }

    @Test
    void block_whenTheBlockFails_theDeclineIsRolledBackToo() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        UUID conversationId = pendingRequest(alice, bob);
        // The requester's account is gone, so creating the block fails after the decline ran.
        jdbc.update("UPDATE users SET deleted_at = NOW() WHERE id = ?", alice.id());

        ResponseEntity<Map> response =
                call(HttpMethod.POST, bob, "/api/v1/conversations/" + conversationId + "/block");

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("pending");
        assertThat(recipientLeft(conversationId, bob)).isFalse();
        assertThat(resolution(conversationId)).isEmpty();
    }

    @Test
    void actions_byTheSenderAStrangerOrOnAResolvedRequest_areNotFound() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        TestUser carol = registerUser("ra_c");
        UUID conversationId = pendingRequest(alice, bob);

        for (TestUser caller : List.of(alice, carol)) {
            for (String[] action :
                    new String[][] {
                        {"POST", "/accept"}, {"DELETE", "/request"}, {"POST", "/block"}
                    }) {
                ResponseEntity<Map> response =
                        call(
                                HttpMethod.valueOf(action[0]),
                                caller,
                                "/api/v1/conversations/" + conversationId + action[1]);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(response.getBody().get("code")).isEqualTo("CONVERSATION_NOT_FOUND");
            }
        }
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("pending");

        call(HttpMethod.POST, bob, "/api/v1/conversations/" + conversationId + "/accept");
        ResponseEntity<Map> again =
                call(
                        HttpMethod.DELETE,
                        bob,
                        "/api/v1/conversations/" + conversationId + "/request");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("accepted");
    }

    @Test
    void block_whenTheRecipientAlreadyBlockedTheSender_stillResolvesTheRequest() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        UUID conversationId = pendingRequest(alice, bob);
        blockBetween(bob, alice);

        ResponseEntity<Map> response =
                call(HttpMethod.POST, bob, "/api/v1/conversations/" + conversationId + "/block");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(conversationRow(conversationId).get("status")).isEqualTo("declined");
        assertThat(recipientLeft(conversationId, bob)).isTrue();
        assertThat(resolution(conversationId)).containsExactly("blocked");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                                Integer.class,
                                bob.id(),
                                alice.id()))
                .isEqualTo(1);
    }

    @Test
    void aBlockInEitherDirection_removesTheRequestFromTheRecipientAndStopsAccepting() {
        for (boolean recipientBlocks : new boolean[] {false, true}) {
            TestUser alice = registerUser("ra_a");
            TestUser bob = registerUser("ra_b");
            UUID conversationId = pendingRequest(alice, bob);
            assertThat(requestCount(bob)).isEqualTo(1);
            if (recipientBlocks) {
                blockBetween(bob, alice);
            } else {
                blockBetween(alice, bob);
            }

            assertThat(folderIds(bob, "requests")).doesNotContain(conversationId);
            assertThat(requestCount(bob)).isZero();
            ResponseEntity<Map> accept =
                    call(
                            HttpMethod.POST,
                            bob,
                            "/api/v1/conversations/" + conversationId + "/accept");
            assertThat(accept.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(accept.getBody().get("code")).isEqualTo("CONVERSATION_NOT_FOUND");
            assertThat(conversationRow(conversationId).get("status")).isEqualTo("pending");
        }
    }

    @Test
    void aBlockInEitherDirection_removesAHiddenRequestFromTheHiddenFolder() {
        TestUser alice = registerUser("ra_a");
        TestUser bob = registerUser("ra_b");
        addHiddenWord(bob, "zorblax");
        UUID conversationId = createDirectOk(alice, bob.id());
        send(alice, conversationId, "you are a Zorblax");
        assertThat(folderIds(bob, "hidden_requests")).containsExactly(conversationId);

        blockBetween(alice, bob);

        assertThat(folderIds(bob, "hidden_requests")).isEmpty();
    }

    @Test
    void provisionerRacingADecline_neverLeavesAnAcceptedConversationWithTheRecipientGone()
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                TestUser alice = registerUser("ra_a");
                TestUser bob = registerUser("ra_b");
                UUID conversationId = pendingRequest(alice, bob);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                Future<Integer> decline =
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return call(
                                                    HttpMethod.DELETE,
                                                    bob,
                                                    "/api/v1/conversations/"
                                                            + conversationId
                                                            + "/request")
                                            .getStatusCode()
                                            .value();
                                });
                Future<Boolean> provision =
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    provisioner.ensureDirectConversation(alice.id(), bob.id());
                                    return true;
                                });
                ready.await();
                start.countDown();

                int declineStatus = decline.get();
                assertThat(provision.get()).isTrue();

                assertThat(declineStatus).isIn(204, 404);
                Map<String, Object> row = conversationRow(conversationId);
                boolean acceptedWithRecipientGone =
                        row.get("status").equals("accepted") && recipientLeft(conversationId, bob);
                assertThat(acceptedWithRecipientGone).isFalse();
                assertThat(row.get("status"))
                        .isEqualTo(declineStatus == 204 ? "declined" : "accepted");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void blockBetween(TestUser blocker, TestUser blocked) {
        jdbc.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)",
                blocker.id(),
                blocked.id());
    }

    private long requestCount(TestUser user) {
        Map<?, ?> data = dataOf(call(HttpMethod.GET, user, "/api/v1/conversations/unread-count"));
        return ((Number) data.get("requestCount")).longValue();
    }

    private UUID pendingRequest(TestUser requester, TestUser recipient) {
        UUID conversationId = createDirectOk(requester, recipient.id());
        assertThat(send(requester, conversationId, "hello").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        return conversationId;
    }

    private List<String> resolution(UUID conversationId) {
        return jdbc.queryForList(
                "SELECT payload -> 'data' ->> 'resolution' FROM outbox_events"
                        + " WHERE event_type = 'message.request.resolved.v1' AND aggregate_id = ?",
                String.class,
                conversationId);
    }

    private boolean blockExists(TestUser blocker, TestUser blocked) {
        return jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM blocks WHERE blocker_id = ? AND blocked_id = ?)",
                Boolean.class,
                blocker.id(),
                blocked.id());
    }

    private int messageCount(UUID conversationId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
                Integer.class,
                conversationId);
    }

    private void addHiddenWord(TestUser user, String phrase) {
        jdbc.update(
                "INSERT INTO user_hidden_words(user_id, phrase, normalized_phrase)"
                        + " VALUES (?, ?, ?)",
                user.id(),
                phrase,
                phrase);
    }

    private Map<String, Object> conversationRow(UUID conversationId) {
        return jdbc.queryForMap(
                "SELECT request_status::text AS status, request_hidden,"
                        + " request_resolved_at IS NOT NULL AS resolved FROM conversations"
                        + " WHERE id = ?",
                conversationId);
    }

    private boolean recipientLeft(UUID conversationId, TestUser recipient) {
        return jdbc.queryForObject(
                "SELECT left_at IS NOT NULL FROM conversation_participants"
                        + " WHERE conversation_id = ? AND user_id = ?",
                Boolean.class,
                conversationId,
                recipient.id());
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

    private UUID createDirectOk(TestUser actor, UUID targetId) {
        ResponseEntity<Map> response =
                rest.exchange(
                        "/api/v1/conversations",
                        HttpMethod.POST,
                        new HttpEntity<>(Map.of("targetUserId", targetId), authHeaders(actor)),
                        Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) dataOf(response).get("id"));
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

    private ResponseEntity<Map> call(HttpMethod method, TestUser actor, String path) {
        return rest.exchange(path, method, new HttpEntity<>(authHeaders(actor)), Map.class);
    }

    private List<UUID> folderIds(TestUser actor, String folder) {
        ResponseEntity<Map> response =
                call(HttpMethod.GET, actor, "/api/v1/conversations?limit=100&folder=" + folder);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> content = (List<Map<?, ?>>) dataOf(response).get("content");
        return content.stream().map(row -> UUID.fromString((String) row.get("id"))).toList();
    }

    private static HttpHeaders authHeaders(TestUser actor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(actor.token());
        return headers;
    }
}
