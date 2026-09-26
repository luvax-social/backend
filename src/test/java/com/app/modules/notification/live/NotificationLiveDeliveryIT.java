package com.app.modules.notification.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
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
 * Proves a notification created via {@link NotificationService#create} is delivered end to end,
 * through the outbox and the notification live fanout exchange, to the recipient's topic only.
 *
 * <p>Exercises {@code NotificationService.create} directly rather than through {@code
 * SocialNotificationConsumer}/{@code CommentNotificationConsumer}/{@code
 * StoryNotificationConsumer}: those consumers' event-to-{@code create()} wiring is already covered
 * by their own existing test suites (e.g. {@code SocialNotificationConsumerIT}), and every one of
 * them funnels through this same {@code create()} call. The surface this phase adds - {@code
 * create()} through the outbox, the fanout exchange, and the push - is type-agnostic, so testing it
 * once through the real service is not a narrower guarantee than testing it three times with three
 * different producers, and duplicating the consumer-level wiring test here would be redundant
 * coverage rather than additional confidence.
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
            "app.outbox.publisher.enabled=true",
            "app.outbox.publisher.initial-delay=PT0S",
            "app.outbox.publisher.fixed-delay=PT0.3S",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class NotificationLiveDeliveryIT {

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
        r.add("JWT_SECRET", () -> "ws-notif-delivery-it-secret-32-characters-min!!");
        r.add("JWT_ISSUER", () -> "https://ws-notif-delivery-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-notif-delivery-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Notif Delivery IT");
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
        String username =
                "ws_notif_dl_" + label + "_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("WS Notif Delivery " + label)
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    private record ConnectedListener(StompSession session, CompletableFuture<byte[]> received) {}

    private ConnectedListener connectAndSubscribe(UUID userId, String token) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        String url =
                "ws://localhost:"
                        + port
                        + "/ws/notifications/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        StompSession session =
                client.connectAsync(url, new StompSessionHandlerAdapter() {})
                        .get(10, TimeUnit.SECONDS);
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
        return new ConnectedListener(session, received);
    }

    @Test
    void notificationCreated_deliveredToRecipientTopicOnly() throws Exception {
        User recipient = activeUser("recipient");
        User actor = activeUser("actor");
        User bystander = activeUser("bystander");

        String recipientToken = jwtTokenProvider.generateAccessToken(recipient.getId(), "USER", 0);
        String bystanderToken = jwtTokenProvider.generateAccessToken(bystander.getId(), "USER", 0);

        ConnectedListener recipientListener =
                connectAndSubscribe(recipient.getId(), recipientToken);
        ConnectedListener bystanderListener =
                connectAndSubscribe(bystander.getId(), bystanderToken);

        notificationService.create(
                actor.getId(), recipient.getId(), NotificationType.FOLLOW, null, null, null);

        byte[] payload = recipientListener.received().get(15, TimeUnit.SECONDS);
        String body = new String(payload);
        assertThat(body).contains("\"event\":\"upserted\"");
        assertThat(body).contains("\"actors\":[{\"id\":\"" + actor.getId() + "\"");
        assertThat(body).contains("\"type\":\"follow\"");
        assertThat(body).contains("\"unseen\":{\"count\":1,\"capped\":false}");

        assertThatThrownBy(() -> bystanderListener.received().get(3, TimeUnit.SECONDS))
                .as("a notification for another user must never reach the bystander's topic")
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void selfNotification_producesNoPush() throws Exception {
        User user = activeUser("self");
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        ConnectedListener listener = connectAndSubscribe(user.getId(), token);

        notificationService.create(
                user.getId(),
                user.getId(),
                NotificationType.LIKE_POST,
                "post",
                UUID.randomUUID(),
                null);

        assertThatThrownBy(() -> listener.received().get(3, TimeUnit.SECONDS))
                .as("a self-notification is suppressed before the outbox is ever touched")
                .isInstanceOf(TimeoutException.class);
    }
}
