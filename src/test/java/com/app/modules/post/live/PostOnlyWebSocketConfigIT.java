package com.app.modules.post.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.app.testsupport.TestContainerImages;

/**
 * Proves the post WebSocket endpoint exists and is reachable when it is the only live tier enabled,
 * and that the comment endpoint is absent in that configuration.
 *
 * <p>This is the post-tier counterpart of the notification-only case. The shared broker config
 * declares {@code @EnableWebSocketMessageBroker} once for the whole application and is gated on the
 * disjunction of every live flag; had the post flag been left out of that expression, enabling only
 * this tier would have produced no STOMP infrastructure at all and a silently non-existent
 * endpoint.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.post.live.enabled=true",
            "app.comment.live.enabled=false",
            "app.notification.live.enabled=false",
            "app.comment.consumer.enabled=false",
            "app.post.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class PostOnlyWebSocketConfigIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "post-only-ws-config-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://post-only-ws-config.it.local");
        r.add("JWT_AUDIENCE", () -> "post-only-ws-config-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Post Only WS IT");
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

    @Test
    void postEndpoint_isReachable_whenItIsTheOnlyLiveTierEnabled() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        String token = jwtTokenProvider.generateAccessToken(insertUser(), "USER", 0);

        StompSession session =
                client.connectAsync(
                                "ws://localhost:"
                                        + port
                                        + "/ws/posts/websocket?ticket="
                                        + webSocketTicketService.issueTicket(token),
                                new StompSessionHandlerAdapter() {})
                        .get(15, TimeUnit.SECONDS);

        assertThat(session.isConnected()).isTrue();
        session.disconnect();
    }

    @Test
    void commentEndpoint_isAbsent_whenOnlyThePostTierIsEnabled() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        String token = jwtTokenProvider.generateAccessToken(insertUser(), "USER", 0);

        assertThatThrownBy(
                        () ->
                                client.connectAsync(
                                                "ws://localhost:"
                                                        + port
                                                        + "/ws/comments/websocket?token="
                                                        + token,
                                                new StompSessionHandlerAdapter() {})
                                        .get(15, TimeUnit.SECONDS))
                .as("a disabled tier must register no endpoint")
                .isInstanceOf(Exception.class);
    }

    private UUID insertUser() {
        String username = "ws_post_only_" + UUID.randomUUID().toString().substring(0, 8);
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@test.local",
                username);
    }
}
