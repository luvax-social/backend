package com.app.common.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
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
 * Proves an ordinary account cannot read other accounts' live frames by subscribing to a wildcard
 * destination. Each module's SUBSCRIBE guard only judges destinations under its own prefix, so a
 * pattern such as {@code /topic/*} or {@code /topic/conversations.*.messages} belongs to no module
 * and the simple broker would otherwise match it against every concrete topic the server publishes
 * to.
 *
 * <p>The server publishes through {@link SimpMessagingTemplate}, the call every fanout consumer
 * makes, to another account's inbox, notification, conversation, comment and post topics. A control
 * subscriber on the last topic published proves all earlier publishes have been relayed before the
 * intruder's received frames are inspected.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.comment.live.enabled=true",
            "app.notification.live.enabled=true",
            "app.message.live.enabled=true",
            "app.post.live.enabled=true",
            "app.comment.consumer.enabled=false",
            "app.notification.consumer.enabled=false",
            "app.post.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class WildcardSubscriptionGuardIT {

    private static final String SECRET = "VICTIM PRIVATE FRAME";

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
        r.add("JWT_SECRET", () -> "wildcard-subscription-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://wildcard-subscription-it.test.local");
        r.add("JWT_AUDIENCE", () -> "wildcard-subscription-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Wildcard Subscription IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private SubscriptionProbe subscriptionProbe;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    private WebSocketStompClient stompClient;
    private StompSession victimSession;
    private StompSession intruderSession;
    private User victim;
    private User intruder;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        victim = activeUser("victim");
        intruder = activeUser("intruder");
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : new StompSession[] {victimSession, intruderSession}) {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/topic/*.*",
                "/topic/*",
                "/topic/#",
                "/topic/**",
                "/topic/inbox.*",
                "/topic/notifications.*",
                "/topic/conversations.*.messages",
                "/topic/conversations.**",
                "/topic/conversations.????????-????-????-????-????????????.messages",
                "/topic/comments.*",
                "/topic/posts.*",
                "/topic/{anything}",
                "/queue/*"
            })
    void wildcardSubscription_isRefusedAndOtherAccountsFramesAreNotDelivered(String pattern)
            throws Exception {
        UUID conversationId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        String controlTopic = "/topic/notifications." + victim.getId();

        CompletableFuture<byte[]> controlFrame = new CompletableFuture<>();
        victimSession = connect(victim, new CompletableFuture<>());
        CompletableFuture<Void> controlRegistered =
                subscriptionProbe.expect(controlTopic, SimpleBrokerMessageHandler.class);
        victimSession.subscribe(controlTopic, frameHandler(controlFrame::complete));
        controlRegistered.get(10, TimeUnit.SECONDS);

        List<String> leaked = new CopyOnWriteArrayList<>();
        CompletableFuture<Throwable> refusal = new CompletableFuture<>();
        intruderSession = connect(intruder, refusal);
        CompletableFuture<Void> intruderRegistered =
                subscriptionProbe.expect(pattern, SimpleBrokerMessageHandler.class);
        intruderSession.subscribe(
                pattern,
                frameHandler(payload -> leaked.add(new String(payload, StandardCharsets.UTF_8))));
        // Either the broker registers the pattern, or the guard refuses it and the server errors
        // the session; whichever happens first settles that the SUBSCRIBE has been decided.
        CompletableFuture.anyOf(intruderRegistered, refusal).get(10, TimeUnit.SECONDS);

        publish("/topic/inbox." + victim.getId());
        publish("/topic/conversations." + conversationId + ".messages");
        publish("/topic/comments." + postId + ".events");
        publish("/topic/posts." + postId + ".likes");
        publish(controlTopic);
        controlFrame.get(10, TimeUnit.SECONDS);

        assertThat(leaked)
                .as("a wildcard subscription must not receive another account's frames")
                .isEmpty();
        assertThat(refusal.get(10, TimeUnit.SECONDS))
                .as("a wildcard SUBSCRIBE must be refused")
                .isNotNull();
    }

    @Test
    void ownTopicSubscription_isStillAcceptedAndDelivered() throws Exception {
        String topic = "/topic/inbox." + victim.getId();
        CompletableFuture<byte[]> received = new CompletableFuture<>();
        victimSession = connect(victim, new CompletableFuture<>());
        CompletableFuture<Void> registered =
                subscriptionProbe.expect(topic, SimpleBrokerMessageHandler.class);
        victimSession.subscribe(topic, frameHandler(received::complete));
        registered.get(10, TimeUnit.SECONDS);

        publish(topic);

        assertThat(new String(received.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8))
                .contains(SECRET);
    }

    private void publish(String topic) {
        // The explicit empty headers choose the Object-payload overload; a bare Map argument is
        // ambiguous with convertAndSend(Object, Map).
        messagingTemplate.convertAndSend(topic, Map.of("secret", SECRET), Map.of());
    }

    private StompFrameHandler frameHandler(Consumer<byte[]> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.accept((byte[]) payload);
            }
        };
    }

    private StompSession connect(User user, CompletableFuture<Throwable> errorSink)
            throws Exception {
        String token = jwtTokenProvider.generateAccessToken(user.getId(), "USER", 0);
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/notifications/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        return stompClient
                .connectAsync(
                        wsUrl,
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                errorSink.complete(
                                        new IllegalStateException("server ERROR: " + headers));
                            }

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
                        })
                .get(15, TimeUnit.SECONDS);
    }

    private User activeUser(String label) {
        String username = "wsg_" + label + "_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("Wildcard Guard " + label)
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    @TestConfiguration
    static class SubscriptionProbeConfig implements WebSocketMessageBrokerConfigurer {

        private final SubscriptionProbe probe = new SubscriptionProbe();

        @Bean
        SubscriptionProbe subscriptionProbe() {
            return probe;
        }

        @Override
        public void configureClientInboundChannel(ChannelRegistration registration) {
            registration.interceptors(probe);
        }
    }

    /**
     * Completes a future once the given handler has finished processing a SUBSCRIBE for a
     * destination: the simple broker sends no receipt for it, so registration is observed
     * server-side.
     */
    static final class SubscriptionProbe implements ExecutorChannelInterceptor {

        private record Expectation(
                String destination,
                Class<? extends MessageHandler> handlerType,
                CompletableFuture<Void> handled) {}

        private final Queue<Expectation> expectations = new ConcurrentLinkedQueue<>();

        CompletableFuture<Void> expect(
                String destination, Class<? extends MessageHandler> handlerType) {
            Expectation expectation =
                    new Expectation(destination, handlerType, new CompletableFuture<>());
            expectations.add(expectation);
            return expectation.handled();
        }

        @Override
        public void afterMessageHandled(
                Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            for (Expectation expectation : expectations) {
                if (StompCommand.SUBSCRIBE == accessor.getCommand()
                        && expectation.destination().equals(accessor.getDestination())
                        && expectation.handlerType().isInstance(handler)
                        && expectations.remove(expectation)) {
                    if (ex == null) {
                        expectation.handled().complete(null);
                    } else {
                        expectation.handled().completeExceptionally(ex);
                    }
                }
            }
        }
    }
}
