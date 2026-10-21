package com.app.modules.message.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.auth.service.WebSocketTicketService;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.message.messaging.MessageEventTypes;
import com.app.testsupport.TestContainerImages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Confirms the per-user inbox topic end to end through the outbox, the RabbitMQ fanout tier and the
 * STOMP broker: the recipient of a message request is told which folder it landed in without any
 * message content, a hidden request is announced as hidden, a recipient who declined hears nothing,
 * a blocked pair hears nothing, and an account cannot subscribe to another account's inbox.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.message.live.enabled=true",
            "app.post.live.enabled=false",
            "app.comment.live.enabled=false",
            "app.comment.consumer.enabled=false",
            "app.post.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=true",
            "app.outbox.publisher.initial-delay=PT0S",
            "app.outbox.publisher.fixed-delay=PT0.3S",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class InboxLiveTopicIT {

    private static final String SECRET_TEXT = "a confidential opening line";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> "message-inbox-live-it-secret-32-chars-min!!!!!");
        r.add("JWT_ISSUER", () -> "https://message-inbox-live-it.test.local");
        r.add("JWT_AUDIENCE", () -> "message-inbox-live-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Inbox Live IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    // Declared at the concrete type because ModerationMailEventHandler injects the abstract
    // sender rather than the interface; an interface-typed override breaks context start.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private final List<StompSession> sessions = new ArrayList<>();
    private WebSocketStompClient stompClient;
    private UUID alice;
    private UUID bob;
    private UUID carol;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        alice = insertUser("inbox_alice_" + suffix());
        bob = insertUser("inbox_bob_" + suffix());
        carol = insertUser("inbox_carol_" + suffix());
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : sessions) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Test
    void firstMessageIntoANewRequest_tellsTheRecipientTheRequestsFolderAndNoContent()
            throws Exception {
        BlockingQueue<byte[]> bobInbox = subscribe(bob, "/topic/inbox." + bob).frames();
        BlockingQueue<byte[]> aliceInbox = subscribe(alice, "/topic/inbox." + alice).frames();
        UUID conversation = createConversation(alice, bob);

        sendText(alice, conversation, SECRET_TEXT);

        String frame = awaitFrameContaining(bobInbox, MessageEventTypes.MESSAGE_SENT_V1);
        JsonNode body = objectMapper.readTree(frame);
        assertThat(body.get("eventType").asString()).isEqualTo(MessageEventTypes.MESSAGE_SENT_V1);
        assertThat(body.get("conversationId").asString()).isEqualTo(conversation.toString());
        assertThat(body.get("folder").asString()).isEqualTo("requests");
        assertThat(frame).doesNotContain(SECRET_TEXT).doesNotContain("\"message\"");
        assertThat(body.propertyNames())
                .containsExactlyInAnyOrder("eventType", "conversationId", "folder");
        assertThat(aliceInbox.poll(4, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void threadTopic_stillCarriesTheContentButNotTheInboxAudienceOrFolder() throws Exception {
        UUID conversation = createConversation(alice, bob);
        BlockingQueue<byte[]> thread =
                subscribe(bob, "/topic/conversations." + conversation + ".messages").frames();

        sendText(alice, conversation, SECRET_TEXT);

        String frame = awaitFrameContaining(thread, MessageEventTypes.MESSAGE_SENT_V1);
        assertThat(frame)
                .contains(SECRET_TEXT)
                .doesNotContain("inboxRecipientIds")
                .doesNotContain("\"folder\"");
    }

    @Test
    void hiddenRequest_isAnnouncedWithTheHiddenRequestsFolder() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO user_hidden_words(user_id, phrase, normalized_phrase)"
                        + " VALUES (?, 'zorblax', 'zorblax')",
                bob);
        BlockingQueue<byte[]> bobInbox = subscribe(bob, "/topic/inbox." + bob).frames();
        UUID conversation = createConversation(alice, bob);

        sendText(alice, conversation, "you are a zorblax");

        String frame = awaitFrameContaining(bobInbox, MessageEventTypes.MESSAGE_SENT_V1);
        assertThat(objectMapper.readTree(frame).get("folder").asString())
                .isEqualTo("hidden_requests");
        assertThat(frame).doesNotContain("zorblax");
    }

    @Test
    void recipientAnswering_tellsTheRequesterTheConversationIsPrimary() throws Exception {
        UUID conversation = createConversation(alice, bob);
        sendText(alice, conversation, SECRET_TEXT);
        BlockingQueue<byte[]> aliceInbox = subscribe(alice, "/topic/inbox." + alice).frames();

        sendText(bob, conversation, "hello back");

        String frame = awaitFrameContaining(aliceInbox, MessageEventTypes.MESSAGE_SENT_V1);
        assertThat(objectMapper.readTree(frame).get("folder").asString()).isEqualTo("primary");
    }

    @Test
    void declinedRecipient_hearsNothingAboutLaterActivityOnTheRequest() throws Exception {
        BlockingQueue<byte[]> bobInbox = subscribe(bob, "/topic/inbox." + bob).frames();
        UUID conversation = createConversation(alice, bob);
        UUID message = sendText(alice, conversation, SECRET_TEXT);
        awaitFrameContaining(bobInbox, MessageEventTypes.MESSAGE_SENT_V1);
        BlockingQueue<byte[]> aliceInbox = subscribe(alice, "/topic/inbox." + alice).frames();

        assertThat(
                        exchange(
                                        bob,
                                        HttpMethod.DELETE,
                                        "/conversations/" + conversation + "/request",
                                        null)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> reacted =
                exchange(
                        alice,
                        HttpMethod.PUT,
                        "/conversations/" + conversation + "/messages/" + message + "/reaction",
                        Map.of("emoji", "👍"));

        assertThat(reacted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobInbox.poll(5, TimeUnit.SECONDS)).isNull();
        assertThat(aliceInbox.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void blockedPair_isRefusedAndNeitherInboxHearsAnything() throws Exception {
        BlockingQueue<byte[]> bobInbox = subscribe(bob, "/topic/inbox." + bob).frames();
        BlockingQueue<byte[]> aliceInbox = subscribe(alice, "/topic/inbox." + alice).frames();
        UUID conversation = createConversation(alice, bob);
        sendText(alice, conversation, SECRET_TEXT);
        awaitFrameContaining(bobInbox, MessageEventTypes.MESSAGE_SENT_V1);
        sendText(bob, conversation, "reply that accepts");
        awaitFrameContaining(aliceInbox, MessageEventTypes.MESSAGE_SENT_V1);
        jdbcTemplate.update("INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)", bob, alice);

        ResponseEntity<String> refused =
                exchange(
                        alice,
                        HttpMethod.POST,
                        "/conversations/" + conversation + "/messages",
                        Map.of("messageType", "text", "content", "are you there"));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bobInbox.poll(5, TimeUnit.SECONDS)).isNull();
        assertThat(aliceInbox.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void anotherAccountsInboxTopic_isRejectedAndNothingIsDelivered() throws Exception {
        Subscription intruder = subscribe(carol, "/topic/inbox." + bob);
        UUID conversation = createConversation(alice, bob);

        sendText(alice, conversation, SECRET_TEXT);

        assertThat(intruder.rejected().get(15, TimeUnit.SECONDS)).isTrue();
        assertThat(intruder.frames().poll(3, TimeUnit.SECONDS)).isNull();
    }

    private record Subscription(
            BlockingQueue<byte[]> frames, CompletableFuture<Boolean> rejected) {}

    private Subscription subscribe(UUID userId, String destination) throws Exception {
        String token = jwtTokenProvider.generateAccessToken(userId, "USER", 0);
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/messages/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        CompletableFuture<Boolean> rejected = new CompletableFuture<>();
        StompSession live =
                stompClient
                        .connectAsync(
                                wsUrl,
                                new StompSessionHandlerAdapter() {
                                    @Override
                                    public void handleFrame(StompHeaders headers, Object payload) {
                                        rejected.complete(true);
                                    }
                                })
                        .get(15, TimeUnit.SECONDS);
        assertThat(live.isConnected()).isTrue();
        sessions.add(live);
        BlockingQueue<byte[]> sink = new LinkedBlockingQueue<>();
        live.subscribe(
                destination,
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        sink.add((byte[]) payload);
                    }
                });
        return new Subscription(sink, rejected);
    }

    // BlockingQueue.poll waits on a condition rather than sleeping, so this does not reintroduce
    // timed polling.
    private String awaitFrameContaining(BlockingQueue<byte[]> sink, String marker)
            throws InterruptedException {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] frame = sink.poll(25, TimeUnit.SECONDS);
            if (frame == null) {
                break;
            }
            String body = new String(frame, StandardCharsets.UTF_8);
            if (body.contains(marker)) {
                return body;
            }
        }
        throw new AssertionError("no frame containing " + marker + " arrived");
    }

    private UUID createConversation(UUID actor, UUID target) throws Exception {
        ResponseEntity<String> response =
                exchange(
                        actor,
                        HttpMethod.POST,
                        "/conversations",
                        Map.of("targetUserId", target.toString()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    private UUID sendText(UUID actor, UUID conversation, String content) throws Exception {
        ResponseEntity<String> response =
                exchange(
                        actor,
                        HttpMethod.POST,
                        "/conversations/" + conversation + "/messages",
                        Map.of("messageType", "text", "content", content));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return idOf(response);
    }

    private UUID idOf(ResponseEntity<String> response) throws Exception {
        return UUID.fromString(
                objectMapper.readTree(response.getBody()).get("data").get("id").asString());
    }

    private ResponseEntity<String> exchange(
            UUID userId, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(userId, "USER", 0));
        RestTemplate client = new RestTemplate();
        // A refusal is an expected answer here, so the client must return it instead of throwing.
        client.setErrorHandler(
                new DefaultResponseErrorHandler() {
                    @Override
                    protected boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                        return false;
                    }
                });
        return client.exchange(
                "http://localhost:" + port + "/api/v1" + path,
                method,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private UUID insertUser(String username) {
        UUID id =
                jdbcTemplate.queryForObject(
                        "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                                + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                        UUID.class,
                        username,
                        username + "@test.local",
                        username);
        jdbcTemplate.update("INSERT INTO user_settings (user_id) VALUES (?)", id);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
