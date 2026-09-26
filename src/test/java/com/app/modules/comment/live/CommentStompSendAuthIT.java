package com.app.modules.comment.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
 * Proves the audit's F-1.2-b exploit no longer reproduces: a STOMP SEND to {@code
 * /app/watch/{postId}} or {@code /app/heartbeat/{postId}} for a post the caller cannot see must be
 * rejected the same way a SUBSCRIBE already was, and a SUBSCRIBE to a comment-topic-shaped
 * destination that does not match the full per-post pattern must now be rejected too.
 *
 * <p>Uses the real native WebSocket transport via {@link WebSocketStompClient}, the same
 * established pattern as {@link CommentWebSocketLiveDeliveryIT}, rather than the SockJS xhr-polling
 * fallback the original audit probe used: the channel interceptor under test sits above the
 * transport and sees an identical {@code Message<?>} either way.
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
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class CommentStompSendAuthIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "stomp-send-auth-it-secret-32-chars-minimum!!!!");
        r.add("JWT_ISSUER", () -> "https://stomp-send-auth-it.test.local");
        r.add("JWT_AUDIENCE", () -> "stomp-send-auth-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App STOMP Send Auth IT");
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
    @Autowired private StringRedisTemplate redisTemplate;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    // Declared at the concrete type rather than at the MailSender interface, because
    // ModerationMailEventHandler injects AbstractTemplateMailSender rather than the
    // interface. An interface-typed override replaces the transport bean with a
    // proxy that is not assignable to it, and the context then fails to start before any
    // assertion runs.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private WebSocketStompClient stompClient;
    private StompSession session;

    private User privateAuthor;
    private Post privatePost;
    private User publicAuthor;
    private Post publicPost;
    private User blockedViewer;
    private User permittedViewer;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        privateAuthor = saveUser("privauth_" + suffix(), true);
        privatePost = savePost(privateAuthor);
        publicAuthor = saveUser("pubauth_" + suffix(), false);
        publicPost = savePost(publicAuthor);
        // Not following privateAuthor, so isVisibleTo(blockedViewer, privatePost) is false.
        blockedViewer = saveUser("blocked_" + suffix(), false);
        // publicPost is visible to anyone; no follow relationship needed.
        permittedViewer = saveUser("permitd_" + suffix(), false);
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    void sendWatch_blockedViewer_doesNotRegisterWatcher() throws Exception {
        long before = watcherCount(privatePost.getId());

        session = connect(blockedViewer);
        session.send("/app/watch/" + privatePost.getId(), new byte[0]);
        Thread.sleep(500); // the SEND is fire-and-forget; allow the server to process it

        assertThat(watcherCount(privatePost.getId()))
                .as("a caller who cannot see the post must not be registered as a watcher")
                .isEqualTo(before);
    }

    @Test
    void sendWatch_permittedViewer_registersWatcher() throws Exception {
        long before = watcherCount(publicPost.getId());

        session = connect(permittedViewer);
        session.send("/app/watch/" + publicPost.getId(), new byte[0]);
        Thread.sleep(500);

        assertThat(watcherCount(publicPost.getId()))
                .as("a caller who can see the post must be registered as a watcher")
                .isEqualTo(before + 1);
    }

    @Test
    void sendHeartbeat_blockedViewer_doesNotRefreshTtl() throws Exception {
        // Seed a real, short-TTL watcher entry first. EXPIRE on a nonexistent key is a silent
        // no-op, so without this the assertion would pass whether or not the guard exists - it
        // must observe an existing TTL failing to be extended, not merely stay absent.
        String key = "comment:watchers:" + privatePost.getId();
        redisTemplate.opsForSet().add(key, "seed-session");
        redisTemplate.expire(key, java.time.Duration.ofSeconds(10));

        session = connect(blockedViewer);
        session.send("/app/heartbeat/" + privatePost.getId(), new byte[0]);
        Thread.sleep(500);

        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl)
                .as(
                        "an unauthorised heartbeat must not re-arm the watcher key's TTL back up"
                                + " to the full 300s window")
                .isNotNull()
                .isLessThanOrEqualTo(10L);
    }

    @Test
    void sendUnwatch_blockedViewer_stillPermitted() throws Exception {
        session = connect(blockedViewer);
        // Deliberately unguarded: must not throw and must not disconnect the session.
        session.send("/app/unwatch/" + privatePost.getId(), new byte[0]);
        Thread.sleep(500);

        assertThat(session.isConnected())
                .as("unwatch has no visibility guard by design; the session must survive it")
                .isTrue();
    }

    @Test
    void subscribe_unmatchedCommentTopic_rejected() throws Exception {
        CompletableFuture<Throwable> error = new CompletableFuture<>();
        session = connect(blockedViewer, error);

        session.subscribe("/topic/comments.notauuid", new StompSessionHandlerAdapter() {});

        Throwable failure = error.get(10, TimeUnit.SECONDS);
        assertThat(failure)
                .as(
                        "a comment-topic-shaped destination outside the full per-post pattern must be"
                                + " rejected, closing the fail-open")
                .isNotNull();
    }

    private long watcherCount(UUID postId) {
        Set<String> members = redisTemplate.opsForSet().members("comment:watchers:" + postId);
        return members == null ? 0 : members.size();
    }

    private StompSession connect(User user) throws Exception {
        return connect(user, null);
    }

    /**
     * A STOMP ERROR frame the server sends in response to a rejected client frame arrives via
     * {@link StompSessionHandler#handleException}, not {@code handleFrame} - that callback is for
     * MESSAGE frames delivered to a subscription.
     */
    private StompSession connect(User user, CompletableFuture<Throwable> errorSink)
            throws Exception {
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/comments/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        StompSessionHandlerAdapter handler =
                errorSink == null
                        ? new StompSessionHandlerAdapter() {}
                        : new StompSessionHandlerAdapter() {
                            @Override
                            public void handleException(
                                    StompSession session,
                                    StompCommand command,
                                    StompHeaders headers,
                                    byte[] payload,
                                    Throwable exception) {
                                errorSink.complete(exception);
                            }

                            @Override
                            public void handleTransportError(
                                    StompSession session, Throwable exception) {
                                errorSink.complete(exception);
                            }
                        };
        return stompClient.connectAsync(wsUrl, handler).get(15, TimeUnit.SECONDS);
    }

    private User saveUser(String username, boolean isPrivate) {
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName(username)
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(isPrivate)
                        .isVerified(false)
                        .build());
    }

    private Post savePost(User owner) {
        return postRepository.save(
                Post.builder()
                        .userId(owner.getId())
                        .postType(PostType.IMAGE)
                        .status(PostStatus.PUBLISHED)
                        .caption("stomp send auth it post")
                        .build());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
