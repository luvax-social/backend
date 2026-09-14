package com.app.modules.comment.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

/**
 * Confirms a real browser-equivalent client can complete the full STOMP-over-WebSocket handshake
 * through the actual Spring Security filter chain, and that a comment created via the real REST
 * endpoint is delivered end to end through the outbox, RabbitMQ fanout, and STOMP broker.
 *
 * <p>Distinct from {@link com.app.common.security.websocket.JwtHandshakeInterceptorTest}, which
 * invokes {@code beforeHandshake} directly and never traverses the security filter chain.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=true",
            "app.comment.consumer.enabled=false",
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
class CommentWebSocketLiveDeliveryIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
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
        r.add("JWT_SECRET", () -> "ws-live-delivery-it-secret-32-chars-minimum!!!");
        r.add("JWT_ISSUER", () -> "https://ws-live-delivery-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-live-delivery-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Live Delivery IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private PostRepository postRepository;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    // Declared at the concrete type rather than at the MailSender interface, because
    // ModerationMailEventHandler and MailCampaignSenderJob both inject
    // AbstractTemplateMailSender. An interface-typed override replaces the transport bean with a
    // proxy that is not assignable to it, and the context then fails to start before any
    // assertion runs.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private WebSocketStompClient stompClient;
    private StompSession session;
    private User author;
    private Post post;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        String username = "ws_author_" + suffix();
        author =
                userRepository.save(
                        User.builder()
                                .username(username)
                                .email(username + "@test.local")
                                .displayName("WS Author")
                                .role(UserRole.USER)
                                .status(UserStatus.ACTIVE)
                                .isPrivate(false)
                                .isVerified(false)
                                .build());
        post =
                postRepository.save(
                        Post.builder()
                                .userId(author.getId())
                                .postType(PostType.IMAGE)
                                .status(PostStatus.PUBLISHED)
                                .caption("ws live delivery it post")
                                .build());
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    void realBrowserClient_connectsThroughSecurityChain_andReceivesLiveCommentEvent()
            throws Exception {
        String token = jwtTokenProvider.generateAccessToken(author.getId(), "USER", 0);
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/comments/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);

        CompletableFuture<byte[]> received = new CompletableFuture<>();
        session =
                stompClient
                        .connectAsync(wsUrl, new StompSessionHandlerAdapter() {})
                        .get(15, TimeUnit.SECONDS);

        assertThat(session.isConnected())
                .as("STOMP session must connect through the real security filter chain")
                .isTrue();

        session.subscribe(
                "/topic/comments." + post.getId() + ".events",
                new org.springframework.messaging.simp.stomp.StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        received.complete((byte[]) payload);
                    }
                });

        createCommentOverHttp(token);

        byte[] payload = received.get(20, TimeUnit.SECONDS);
        String body = new String(payload, StandardCharsets.UTF_8);
        assertThat(body).contains("comment.created.v1");

        // The STOMP converter must serialize with the application's own JsonMapper, not the
        // private one Spring builds by default, which reads no spring.jackson.* configuration and
        // rendered timestamps in the JVM's local offset while REST rendered the same field in UTC.
        java.util.regex.Matcher timestamps =
                java.util.regex.Pattern.compile("\"(?:createdAt|updatedAt)\":\"([^\"]+)\"")
                        .matcher(body);
        int asserted = 0;
        while (timestamps.find()) {
            assertThat(timestamps.group(1))
                    .as("WebSocket payload timestamp must render in UTC, matching the REST surface")
                    .endsWith("Z");
            asserted++;
        }
        assertThat(asserted)
                .as("payload must carry at least one timestamp for this assertion to mean anything")
                .isGreaterThan(0);
    }

    private void createCommentOverHttp(String token) {
        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body =
                Map.of(
                        "postId",
                        post.getId().toString(),
                        "content",
                        "live delivery integration test comment");
        rest.exchange(
                "http://localhost:" + port + "/api/v1/posts/" + post.getId() + "/comments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
