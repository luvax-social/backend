package com.app.modules.notification.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
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
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

/**
 * Measures observed end-to-end push latency (notification creation to STOMP frame receipt) against
 * the real production {@code app.outbox.publisher.fixed-delay} default (PT1S) rather than the
 * PT0.3S test-only override {@link NotificationLiveDeliveryIT} uses for fast execution.
 *
 * <p>Neither {@code initial-delay} nor the retry backoffs are overridden. The first notification in
 * this test is a warm-up, discarded from the measurement: a fresh context has not yet reached its
 * first {@code fixed-delay} tick, so timing the very first publish would measure leftover {@code
 * initial-delay} (PT10S default) rather than steady-state polling latency. The second notification
 * is the one measured, against an outbox publisher already in its steady polling cadence.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.notification.live.enabled=true",
            "app.comment.live.enabled=false",
            "app.notification.consumer.enabled=false",
            "app.comment.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class NotificationPushLatencyIT {

    private static final Logger log = LoggerFactory.getLogger(NotificationPushLatencyIT.class);

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
        r.add("JWT_SECRET", () -> "ws-notif-latency-it-secret-32-characters-min!!");
        r.add("JWT_ISSUER", () -> "https://ws-notif-latency-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-notif-latency-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Notif Latency IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationService notificationService;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    private User activeUser(String label) {
        String username = "wsnl_" + label + "_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("WS Notif Lat " + label)
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    private CompletableFuture<byte[]> subscribe(StompSession session, UUID userId) {
        CompletableFuture<byte[]> received = new CompletableFuture<>();
        session.subscribe(
                "/topic/notifications." + userId,
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        received.complete((byte[]) payload);
                    }
                });
        return received;
    }

    @Test
    void steadyStatePushLatency_underDefaultPollingInterval() throws Exception {
        User recipient = activeUser("recipient");
        User actor = activeUser("actor");
        String token = jwtTokenProvider.generateAccessToken(recipient.getId(), "USER", 0);

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        String url =
                "ws://localhost:"
                        + port
                        + "/ws/notifications/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        StompSession session =
                client.connectAsync(url, new StompSessionHandlerAdapter() {})
                        .get(10, TimeUnit.SECONDS);

        // Warm-up: absorbs the leftover app.outbox.publisher.initial-delay (PT10S default) so the
        // measured notification below is timed against steady-state fixed-delay polling only.
        CompletableFuture<byte[]> warmUp = subscribe(session, recipient.getId());
        Instant warmUpStart = Instant.now();
        notificationService.create(
                actor.getId(), recipient.getId(), NotificationType.FOLLOW, null, null, null);
        warmUp.get(15, TimeUnit.SECONDS);
        log.info(
                "Warm-up latency (includes leftover initial-delay): {} ms",
                Duration.between(warmUpStart, Instant.now()).toMillis());

        CompletableFuture<byte[]> measured = subscribe(session, recipient.getId());
        Instant start = Instant.now();
        notificationService.create(
                actor.getId(),
                recipient.getId(),
                NotificationType.LIKE_POST,
                "post",
                UUID.randomUUID(),
                null);
        measured.get(10, TimeUnit.SECONDS);
        Duration observed = Duration.between(start, Instant.now());

        log.info("Observed end-to-end notification push latency: {} ms", observed.toMillis());
        assertThat(observed)
                .as("steady-state push latency must stay within a small multiple of the 1s poll")
                .isLessThan(Duration.ofSeconds(3));
    }
}
