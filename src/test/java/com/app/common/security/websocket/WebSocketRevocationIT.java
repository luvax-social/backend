package com.app.common.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.security.jwt.JwtClaims;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.common.security.service.TokenBlacklistService;
import com.app.modules.auth.service.WebSocketTicketService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the revocation sweep in {@link WebSocketRevocationSweepService} terminates a session
 * established with a valid token once the account is banned, suspended, or the token is
 * blacklisted, without waiting for the token's natural expiry.
 *
 * <p>Invokes {@link WebSocketRevocationSweepService#sweep()} directly rather than waiting for the
 * {@code @Scheduled} timer: the production method is a plain, synchronously callable method, and
 * calling it directly makes the test deterministic instead of racing a background timer.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=true",
            "app.comment.consumer.enabled=false",
            // Enabled so the direct-message endpoint is registered too. Revocation must reach every
            // live endpoint, not only the one the comment module contributes.
            "app.message.live.enabled=true",
            "app.message.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class WebSocketRevocationIT {

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
        r.add("JWT_SECRET", () -> "ws-revocation-it-secret-32-characters-minimum!!");
        r.add("JWT_ISSUER", () -> "https://ws-revocation-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-revocation-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Revocation IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenBlacklistService tokenBlacklistService;
    @Autowired private WebSocketRevocationSweepService sweepService;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    private User activeUser() {
        String username = "ws_revoke_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("WS Revocation Target")
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    private RecordingHandler connect(String token) throws Exception {
        return connectTo("/ws/comments", token);
    }

    private RecordingHandler connectTo(String endpoint, String token) throws Exception {
        RecordingHandler handler = new RecordingHandler();
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url =
                "ws://localhost:"
                        + port
                        + endpoint
                        + "/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        WebSocketSession session = client.execute(handler, url).get(10, TimeUnit.SECONDS);
        assertThat(session.isOpen()).as("session must connect before revocation").isTrue();
        return handler;
    }

    @Test
    void bannedAccount_sessionClosedBySweep() throws Exception {
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        RecordingHandler handler = connect(token);

        user.setStatus(UserStatus.BANNED);
        userRepository.save(user);
        sweepService.sweep();

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void suspendedAccount_sessionClosedBySweep() throws Exception {
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        RecordingHandler handler = connect(token);

        user.setStatus(UserStatus.SUSPENDED);
        userRepository.save(user);
        sweepService.sweep();

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void blacklistedToken_sessionClosedBySweep() throws Exception {
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        RecordingHandler handler = connect(token);

        JwtClaims claims = jwtTokenProvider.validateAndParse(token);
        tokenBlacklistService.blacklist(claims.jti(), 900);
        sweepService.sweep();

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    /**
     * The direct-message endpoint must be revocable on the same terms as every other live endpoint.
     *
     * <p>It previously was not. The message module supplied its own handshake interceptor which
     * stored only the resolved principal, while {@link
     * SessionTrackingWebSocketHandlerDecoratorFactory} enrols a session only when the raw token is
     * present under {@link JwtHandshakeInterceptor#TOKEN_ATTRIBUTE}. A direct-message socket
     * therefore never entered {@link WebSocketSessionRegistry}, the sweep had nothing to examine,
     * and logging out, banning, or suspending an account left its open conversation socket alive
     * until the access token expired on its own.
     */
    @Test
    void messageSocket_blacklistedToken_sessionClosedBySweep() throws Exception {
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        RecordingHandler handler = connectTo("/ws/messages", token);

        JwtClaims claims = jwtTokenProvider.validateAndParse(token);
        tokenBlacklistService.blacklist(claims.jti(), 900);
        sweepService.sweep();

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void messageSocket_bannedAccount_sessionClosedBySweep() throws Exception {
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        RecordingHandler handler = connectTo("/ws/messages", token);

        user.setStatus(UserStatus.BANNED);
        userRepository.save(user);
        sweepService.sweep();

        CloseStatus closeStatus = handler.awaitClose();
        assertThat(closeStatus.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void stillValidSession_survivesSweep() throws Exception {
        User user = activeUser();
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        RecordingHandler handler = connect(token);

        sweepService.sweep();

        assertThat(handler.closed.isDone())
                .as("a session whose token still resolves must not be closed by the sweep")
                .isFalse();
    }

    private static final class RecordingHandler implements WebSocketHandler {

        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private volatile Throwable transportError;

        /**
         * Waits for the server-initiated close and returns its status.
         *
         * <p>A transport error is deliberately not treated as a close. When the server closes the
         * session, the client stack replies with its own close frame, and under a loaded suite that
         * blocking write can time out. Completing {@code closed} exceptionally from {@link
         * #handleTransportError} let that teardown noise win the race against {@link
         * #afterConnectionClosed} and fail a test whose subject, the server-side close, had already
         * succeeded. Any recorded error is still surfaced if the close never arrives.
         */
        CloseStatus awaitClose() throws Exception {
            try {
                return closed.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                if (transportError != null) {
                    throw new AssertionError(
                            "session never closed; transport error was: " + transportError,
                            transportError);
                }
                throw e;
            }
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            // No action required; the test asserts on session.isOpen() after connect() returns.
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
            // No STOMP frames are sent by these tests; nothing to handle.
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            transportError = exception;
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            closed.complete(closeStatus);
        }

        @Override
        public boolean supportsPartialMessages() {
            return false;
        }
    }
}
