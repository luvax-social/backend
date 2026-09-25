package com.app.modules.comment.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.auth.service.WebSocketTicketService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the WebSocket handshake rejects a token belonging to a non-{@code ACTIVE} account, the
 * same guarantee {@link com.app.common.security.filter.JwtAuthenticationFilter} already provides on
 * the REST path.
 *
 * <p>The token itself is otherwise valid: correctly signed, not expired, not blacklisted. Only the
 * account's status changed after issuance. As of writing, {@link
 * com.app.common.security.websocket.JwtHandshakeInterceptor} never resolves account status and
 * hardcodes {@code "ACTIVE"} on the resolved principal, so this class is expected to fail RED
 * against current code.
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
class CommentWebSocketAccountStatusIT {

    private static final Logger log =
            LoggerFactory.getLogger(CommentWebSocketAccountStatusIT.class);

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
        r.add("JWT_SECRET", () -> "ws-account-status-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://ws-account-status-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-account-status-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Account Status IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    private User userWithStatus(UserStatus status) {
        String username = "ws_status_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("WS Account Status Target")
                        .role(UserRole.USER)
                        .status(status)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    private Throwable attemptConnect(String token) {
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/comments/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        CompletableFuture<StompSession> future =
                client.connectAsync(wsUrl, new StompSessionHandlerAdapter() {});
        return catchThrowable(() -> future.get(10, TimeUnit.SECONDS));
    }

    @Test
    void handshake_bannedUserValidToken_doesNotEstablishStompSession() {
        User banned = userWithStatus(UserStatus.BANNED);
        String token = jwtTokenProvider.generateAccessToken(banned.getId(), "USER", 0);

        Throwable thrown = attemptConnect(token);

        assertThat(thrown)
                .as(
                        "a valid, non-expired, non-blacklisted token for a BANNED account must not"
                                + " yield a STOMP session")
                .isNotNull();
        log.info("banned user handshake observed failure: {}", rootMessage(thrown));
    }

    @Test
    void handshake_suspendedUserValidToken_doesNotEstablishStompSession() {
        User suspended = userWithStatus(UserStatus.SUSPENDED);
        String token = jwtTokenProvider.generateAccessToken(suspended.getId(), "USER", 0);

        Throwable thrown = attemptConnect(token);

        assertThat(thrown)
                .as(
                        "a valid, non-expired, non-blacklisted token for a SUSPENDED account must not"
                                + " yield a STOMP session")
                .isNotNull();
        log.info("suspended user handshake observed failure: {}", rootMessage(thrown));
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cursor = thrown;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getClass().getSimpleName() + ": " + cursor.getMessage();
    }
}
