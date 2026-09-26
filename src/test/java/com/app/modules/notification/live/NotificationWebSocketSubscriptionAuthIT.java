package com.app.modules.notification.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.simp.stomp.StompCommand;
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
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Proves subscription authorization for the per-user notification topic: a user may subscribe only
 * to their own {@code /topic/notifications.{userId}}, an attempt on another user's topic is
 * rejected and logged, and the guard applies to a session established through either WebSocket
 * endpoint since both share the one inbound channel.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=true",
            "app.notification.live.enabled=true",
            "app.comment.consumer.enabled=false",
            "app.notification.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class NotificationWebSocketSubscriptionAuthIT {

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
        r.add("JWT_SECRET", () -> "ws-notif-authz-it-secret-32-characters-min!!");
        r.add("JWT_ISSUER", () -> "https://ws-notif-authz-it.test.local");
        r.add("JWT_AUDIENCE", () -> "ws-notif-authz-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App WS Notif Authz IT");
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

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(NotificationWebSocketAuthInterceptor.class))
                .addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(NotificationWebSocketAuthInterceptor.class))
                .detachAppender(logAppender);
    }

    private User activeUser(String label) {
        String username = "ws_notif_" + label + "_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("WS Notif " + label)
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    /**
     * Connects and returns the session plus a future that resolves when the server signals error.
     */
    private record ConnectedSession(
            StompSession session, CompletableFuture<Throwable> serverError) {}

    private ConnectedSession connectWithErrorCapture(String endpoint, String token)
            throws Exception {
        CompletableFuture<Throwable> serverError = new CompletableFuture<>();
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        String url =
                "ws://localhost:"
                        + port
                        + endpoint
                        + "/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        StompSession session =
                client.connectAsync(
                                url,
                                new StompSessionHandlerAdapter() {
                                    @Override
                                    public void handleException(
                                            StompSession s,
                                            StompCommand command,
                                            StompHeaders headers,
                                            byte[] payload,
                                            Throwable exception) {
                                        serverError.complete(exception);
                                    }

                                    @Override
                                    public void handleTransportError(
                                            StompSession s, Throwable exception) {
                                        serverError.complete(exception);
                                    }

                                    @Override
                                    public void handleFrame(StompHeaders headers, Object payload) {
                                        serverError.complete(
                                                new IllegalStateException(
                                                        "Unexpected session-level frame: "
                                                                + headers));
                                    }
                                })
                        .get(10, TimeUnit.SECONDS);
        return new ConnectedSession(session, serverError);
    }

    private void subscribeExpectingNoDelivery(
            StompSession session, String destination, CompletableFuture<Throwable> serverError) {
        session.subscribe(
                destination,
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        serverError.completeExceptionally(
                                new AssertionError("Subscription should not have succeeded"));
                    }
                });
    }

    @Test
    void subscribeToAnotherUsersTopic_rejectedAndLogged() throws Exception {
        User self = activeUser("self");
        User other = activeUser("other");
        String token = jwtTokenProvider.generateAccessToken(self.getId(), "USER", 0);

        ConnectedSession connected = connectWithErrorCapture("/ws/notifications", token);
        subscribeExpectingNoDelivery(
                connected.session(),
                "/topic/notifications." + other.getId(),
                connected.serverError());

        Throwable observed = connected.serverError().get(10, TimeUnit.SECONDS);
        assertThat(observed).isNotNull();

        assertLoggedRejection(self.getId(), other.getId());
    }

    @Test
    void subscribeToMalformedTopic_rejected() throws Exception {
        User self = activeUser("self");
        String token = jwtTokenProvider.generateAccessToken(self.getId(), "USER", 0);

        ConnectedSession connected = connectWithErrorCapture("/ws/notifications", token);
        subscribeExpectingNoDelivery(
                connected.session(),
                "/topic/notifications." + self.getId() + ".extra",
                connected.serverError());

        Throwable observed = connected.serverError().get(10, TimeUnit.SECONDS);
        assertThat(observed).isNotNull();
    }

    /**
     * Proves the SUBSCRIBE to the caller's own topic is accepted, not proves delivery: end-to-end
     * delivery through the production write path is covered once that path exists. A bounded wait
     * for the server's rejection signal that does not resolve is the proof of acceptance here - the
     * same negative-outcome pattern used by {@code WebSocketRevocationIT.stillValidSession_...},
     * except a network round trip is involved so the wait cannot be zero.
     */
    @Test
    void subscribeToOwnTopic_succeeds() throws Exception {
        User self = activeUser("self");
        String token = jwtTokenProvider.generateAccessToken(self.getId(), "USER", 0);

        ConnectedSession connected = connectWithErrorCapture("/ws/notifications", token);
        connected
                .session()
                .subscribe(
                        "/topic/notifications." + self.getId(),
                        new StompFrameHandler() {
                            @Override
                            public Type getPayloadType(StompHeaders headers) {
                                return byte[].class;
                            }

                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                // No message is published in this test; only acceptance of the
                                // SUBSCRIBE itself is under test.
                            }
                        });

        assertThatThrownBy(() -> connected.serverError().get(3, TimeUnit.SECONDS))
                .as("a subscription to the caller's own topic must not be rejected")
                .isInstanceOf(TimeoutException.class);
    }

    @Test
    void sessionEstablishedOnCommentEndpoint_stillRejectedForNotificationTopic() throws Exception {
        User self = activeUser("self");
        User other = activeUser("other");
        String token = jwtTokenProvider.generateAccessToken(self.getId(), "USER", 0);

        ConnectedSession connected = connectWithErrorCapture("/ws/comments", token);
        subscribeExpectingNoDelivery(
                connected.session(),
                "/topic/notifications." + other.getId(),
                connected.serverError());

        Throwable observed = connected.serverError().get(10, TimeUnit.SECONDS);
        assertThat(observed).isNotNull();
    }

    private void assertLoggedRejection(UUID expectedViewer, UUID expectedOther) {
        List<ILoggingEvent> warnEvents =
                logAppender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnEvents).isNotEmpty();
        ILoggingEvent event = warnEvents.get(0);
        assertThat(event.getFormattedMessage())
                .contains("userId=" + expectedViewer)
                .contains("destination=/topic/notifications." + expectedOther);
    }
}
