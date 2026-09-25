package com.app.modules.notification.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
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
 * Proves the notification WebSocket endpoint exists and is reachable when {@code
 * app.comment.live.enabled=false} and only {@code app.notification.live.enabled=true} - the
 * configuration a cautious rollout would choose, and the one that would silently produce no STOMP
 * infrastructure at all if {@code @EnableWebSocketMessageBroker} still lived on the comment
 * module's per-endpoint config instead of the shared {@link
 * com.app.common.config.websocket.WebSocketBrokerConfig}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=false",
            "app.notification.live.enabled=true",
            "app.comment.consumer.enabled=false",
            "app.notification.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class NotificationOnlyWebSocketConfigIT {

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
        r.add("JWT_SECRET", () -> "ws-notif-only-config-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://ws-notif-only-config-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-notif-only-config-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Notif Only Config IT");
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

    @Test
    void notificationEndpoint_reachable_whenCommentLiveDisabled() throws Exception {
        String username = "ws_notif_only_" + UUID.randomUUID().toString().substring(0, 8);
        User user =
                userRepository.save(
                        User.builder()
                                .username(username)
                                .email(username + "@test.local")
                                .displayName("WS Notif Only Config Target")
                                .role(UserRole.USER)
                                .status(UserStatus.ACTIVE)
                                .isPrivate(false)
                                .isVerified(false)
                                .build());
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        String url =
                "ws://localhost:"
                        + port
                        + "/ws/notifications/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        StompSession session =
                client.connectAsync(url, new StompSessionHandlerAdapter() {})
                        .get(10, TimeUnit.SECONDS);

        assertThat(session.isConnected())
                .as(
                        "the notification STOMP endpoint must be reachable when comment live is"
                                + " disabled and notification live is the only enabled surface")
                .isTrue();
    }
}
