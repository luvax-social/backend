package com.app.modules.message.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
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

/**
 * Confirms a reaction reaches the other participant's subscription to the thread topic end to end
 * through the outbox, the RabbitMQ fanout tier and the STOMP broker, and that nothing reaches an
 * account that is not a participant or a pair that blocks each other.
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
class MessageReactionLiveIT {

    private static final String THUMBS_UP = "👍";

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
        r.add("JWT_SECRET", () -> "message-reaction-live-it-secret-32-chars-min!!!");
        r.add("JWT_ISSUER", () -> "https://message-reaction-live-it.test.local");
        r.add("JWT_AUDIENCE", () -> "message-reaction-live-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Message Live IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

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
    private UUID conversation;
    private UUID message;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        alice = insertUser("rx_live_alice_" + suffix());
        bob = insertUser("rx_live_bob_" + suffix());
        carol = insertUser("rx_live_carol_" + suffix());
        conversation =
                jdbcTemplate.queryForObject(
                        "INSERT INTO conversations DEFAULT VALUES RETURNING id", UUID.class);
        for (UUID member : List.of(alice, bob)) {
            jdbcTemplate.update(
                    "INSERT INTO conversation_participants(conversation_id, user_id) VALUES (?, ?)",
                    conversation,
                    member);
        }
        message =
                jdbcTemplate.queryForObject(
                        "INSERT INTO messages(conversation_id, sender_id, message_type, content)"
                                + " VALUES (?, ?, 'text', 'react to me') RETURNING id",
                        UUID.class,
                        conversation,
                        bob);
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
    void otherParticipant_receivesTheReactionAndTheRemoval() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(bob);

        assertThat(put(alice, THUMBS_UP).getStatusCode()).isEqualTo(HttpStatus.OK);
        String set = awaitFrameContaining(received, MessageEventTypes.MESSAGE_REACTION_CHANGED_V1);

        assertThat(set)
                .contains("\"conversationId\":\"" + conversation + "\"")
                .contains("\"messageId\":\"" + message + "\"")
                .contains("\"userId\":\"" + alice + "\"")
                .contains(THUMBS_UP)
                .doesNotContain("inboxRecipientIds");

        assertThat(delete(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String removed =
                awaitFrameContaining(received, MessageEventTypes.MESSAGE_REACTION_CHANGED_V1);

        assertThat(removed).contains("\"userId\":\"" + alice + "\"").doesNotContain("\"emoji\"");
    }

    @Test
    void nonParticipant_receivesNothingWhileTheParticipantDoes() throws Exception {
        BlockingQueue<byte[]> participant = subscribeAs(bob);
        BlockingQueue<byte[]> outsider = subscribeAs(carol);

        assertThat(put(alice, THUMBS_UP).getStatusCode()).isEqualTo(HttpStatus.OK);

        awaitFrameContaining(participant, MessageEventTypes.MESSAGE_REACTION_CHANGED_V1);
        assertThat(outsider.poll(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void blockedPair_isRefusedAndNoFrameIsPublished() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(bob);
        put(alice, THUMBS_UP);
        awaitFrameContaining(received, MessageEventTypes.MESSAGE_REACTION_CHANGED_V1);
        jdbcTemplate.update("INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)", bob, alice);

        ResponseEntity<String> refused = put(alice, "❤️");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(bob, "❤️").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void tombstonedMessage_isRefusedAndNoFrameIsPublished() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(bob);
        jdbcTemplate.update("UPDATE messages SET admin_removed_at = NOW() WHERE id = ?", message);

        ResponseEntity<String> refused = put(alice, THUMBS_UP);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(received.poll(5, TimeUnit.SECONDS)).isNull();
    }

    private BlockingQueue<byte[]> subscribeAs(UUID userId) throws Exception {
        String token = jwtTokenProvider.generateAccessToken(userId, "USER", 0);
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/messages/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        StompSession live =
                stompClient
                        .connectAsync(wsUrl, new StompSessionHandlerAdapter() {})
                        .get(15, TimeUnit.SECONDS);
        assertThat(live.isConnected()).isTrue();
        sessions.add(live);
        BlockingQueue<byte[]> sink = new LinkedBlockingQueue<>();
        live.subscribe(
                "/topic/conversations." + conversation + ".messages",
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
        return sink;
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

    private ResponseEntity<String> put(UUID userId, String emoji) {
        return exchange(userId, HttpMethod.PUT, Map.of("emoji", emoji));
    }

    private ResponseEntity<String> delete(UUID userId) {
        return exchange(userId, HttpMethod.DELETE, null);
    }

    private ResponseEntity<String> exchange(UUID userId, HttpMethod method, Object body) {
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
                "http://localhost:"
                        + port
                        + "/api/v1/conversations/"
                        + conversation
                        + "/messages/"
                        + message
                        + "/reaction",
                method,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private UUID insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@test.local",
                username);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
