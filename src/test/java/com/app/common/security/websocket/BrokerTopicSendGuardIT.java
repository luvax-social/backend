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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
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
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;

/**
 * Proves the audit's CRIT-1 exploit no longer reproduces: a client SEND addressed directly at a
 * broker-relayed {@code /topic/**} destination - another user's notification topic, or any post's
 * comment-events topic - must be rejected before the broker relays it to any subscriber, while a
 * server-originated publish through {@link SimpMessagingTemplate} (the mechanism every fanout
 * consumer uses in production) must still be delivered, and a legitimate client SEND to an {@code
 * /app/**} destination must still reach its {@code @MessageMapping} handler.
 *
 * <p>Uses the real native WebSocket transport via {@link WebSocketStompClient}, matching {@link
 * com.app.modules.comment.live.CommentWebSocketLiveDeliveryIT}: the interceptor under test sits
 * above the transport and sees an identical {@code Message<?>} regardless of transport.
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
class BrokerTopicSendGuardIT {

    private static final String SENTINEL = "SENTINEL PUBLISHED BY TEST";

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
        r.add("JWT_SECRET", () -> "broker-send-guard-it-secret-32-chars-minimum!");
        r.add("JWT_ISSUER", () -> "https://broker-send-guard-it.test.local");
        r.add("JWT_AUDIENCE", () -> "broker-send-guard-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Broker Send Guard IT");
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
    @Autowired private SimpMessagingTemplate messagingTemplate;
    @Autowired private HandledFrameProbe handledFrameProbe;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    private WebSocketStompClient stompClient;
    private StompSession victimSession;
    private StompSession attackerSession;

    private User victim;
    private User attacker;
    private User author;
    private Post post;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        victim = activeUser("victim");
        attacker = activeUser("attacker");
        author = activeUser("author");
        post =
                postRepository.save(
                        Post.builder()
                                .userId(author.getId())
                                .postType(PostType.IMAGE)
                                .status(PostStatus.PUBLISHED)
                                .caption("broker send guard it post")
                                .build());
    }

    @AfterEach
    void tearDown() {
        if (victimSession != null && victimSession.isConnected()) {
            victimSession.disconnect();
        }
        if (attackerSession != null && attackerSession.isConnected()) {
            attackerSession.disconnect();
        }
    }

    @Test
    void sendToAnotherUsersNotificationTopic_rejectedAndVictimReceivesNothing() throws Exception {
        String topic = "/topic/notifications." + victim.getId();
        List<String> victimFrames = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> sentinelArrived = new CompletableFuture<>();
        victimSession = connect(victim, null);
        awaitSubscribed(
                topic,
                () ->
                        victimSession.subscribe(
                                topic, recordingFrameHandler(victimFrames, sentinelArrived)));

        CompletableFuture<Throwable> attackerError = new CompletableFuture<>();
        attackerSession = connect(attacker, attackerError);
        attackerSession.send(
                "/topic/notifications." + victim.getId(),
                "{\"message\":\"FORGED BY TEST\"}".getBytes(StandardCharsets.UTF_8));

        Throwable rejection = attackerError.get(10, TimeUnit.SECONDS);
        assertThat(rejection)
                .as("a client SEND directly at another user's notification topic must be rejected")
                .isNotNull();

        publishSentinel(topic, Map.of("message", SENTINEL));
        sentinelArrived.get(10, TimeUnit.SECONDS);
        assertThat(victimFrames)
                .as("the victim must not receive the forged frame")
                .noneMatch(frame -> frame.contains("FORGED"));
    }

    @Test
    void sendToCommentEventsTopic_rejectedAndListenerReceivesNothing() throws Exception {
        String topic = "/topic/comments." + post.getId() + ".events";
        List<String> listenerFrames = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> sentinelArrived = new CompletableFuture<>();
        victimSession = connect(victim, null);
        awaitSubscribed(
                topic,
                () ->
                        victimSession.subscribe(
                                topic, recordingFrameHandler(listenerFrames, sentinelArrived)));

        CompletableFuture<Throwable> attackerError = new CompletableFuture<>();
        attackerSession = connect(attacker, attackerError);
        attackerSession.send(
                "/topic/comments." + post.getId() + ".events",
                "{\"eventType\":\"comment.created.v1\",\"data\":{\"content\":\"FORGED\"}}"
                        .getBytes(StandardCharsets.UTF_8));

        Throwable rejection = attackerError.get(10, TimeUnit.SECONDS);
        assertThat(rejection)
                .as("a client SEND directly at a post's comment-events topic must be rejected")
                .isNotNull();

        publishSentinel(
                topic,
                Map.of("eventType", "comment.created.v1", "data", Map.of("content", SENTINEL)));
        sentinelArrived.get(10, TimeUnit.SECONDS);
        assertThat(listenerFrames)
                .as("the listener must not receive the forged comment event")
                .noneMatch(frame -> frame.contains("FORGED"));
    }

    @Test
    void genuineServerPublish_toNotificationTopic_stillReachesSubscriber() throws Exception {
        CompletableFuture<byte[]> received = new CompletableFuture<>();
        victimSession = connect(victim, null);
        String topic = "/topic/notifications." + victim.getId();
        awaitSubscribed(topic, () -> victimSession.subscribe(topic, byteFrameHandler(received)));

        // The exact call NotificationLiveFanoutConsumer makes in production: SimpMessagingTemplate
        // writes directly to the broker's outbound channel and never traverses
        // clientInboundChannel,
        // so the new guard - which only inspects client SEND frames - must not affect it.
        // Explicit empty headers disambiguates the overload: Map.of(...) as a bare second
        // argument is ambiguous between convertAndSend(D, Object) and convertAndSend(Object,
        // Map<String,Object>), since the payload here happens to itself be a Map.
        messagingTemplate.convertAndSend(
                "/topic/notifications." + victim.getId(),
                Map.of("message", "genuine event"),
                Map.of());

        byte[] payload = received.get(10, TimeUnit.SECONDS);
        assertThat(new String(payload, StandardCharsets.UTF_8)).contains("genuine event");
    }

    @Test
    void genuineServerPublish_toCommentEventsTopic_stillReachesSubscriber() throws Exception {
        CompletableFuture<byte[]> received = new CompletableFuture<>();
        victimSession = connect(victim, null);
        String topic = "/topic/comments." + post.getId() + ".events";
        awaitSubscribed(topic, () -> victimSession.subscribe(topic, byteFrameHandler(received)));

        messagingTemplate.convertAndSend(
                "/topic/comments." + post.getId() + ".events",
                Map.of("eventType", "comment.created.v1", "data", Map.of("content", "genuine")),
                Map.of());

        byte[] payload = received.get(10, TimeUnit.SECONDS);
        assertThat(new String(payload, StandardCharsets.UTF_8)).contains("comment.created.v1");
    }

    @Test
    void sendToApplicationDestination_stillPermitted() throws Exception {
        victimSession = connect(victim, null);
        // /app/watch has its own visibility guard (CommentStompSendAuthIT); this only proves the
        // new broker-wide guard does not itself reject a legitimate /app-prefixed SEND before that
        // guard ever runs, by asserting the session survives it rather than being torn down.
        String destination = "/app/watch/" + post.getId();
        CompletableFuture<Void> handled =
                handledFrameProbe.expect(
                        StompCommand.SEND, destination, SimpAnnotationMethodMessageHandler.class);
        victimSession.send(destination, new byte[0]);
        // Reaching the @MessageMapping handler proves no inbound interceptor rejected the frame,
        // since a rejection throws before the frame is dispatched to any handler.
        handled.get(10, TimeUnit.SECONDS);

        assertThat(victimSession.isConnected())
                .as("a SEND to an /app destination must not be rejected by the broker-wide guard")
                .isTrue();
    }

    /**
     * The simple broker sends no RECEIPT for SUBSCRIBE, so readiness is observed server-side: the
     * future completes once the broker handler has registered the subscription.
     */
    private void awaitSubscribed(String topic, Runnable subscribe) throws Exception {
        CompletableFuture<Void> registered =
                handledFrameProbe.expect(
                        StompCommand.SUBSCRIBE, topic, SimpleBrokerMessageHandler.class);
        subscribe.run();
        registered.get(10, TimeUnit.SECONDS);
    }

    /**
     * The guard rejects a forged SEND before it is dispatched, so once the attacker's ERROR frame
     * has arrived the forged frame can no longer be delivered; a server publish then reaching the
     * subscriber proves the subscription was live, so the absence of the forged frame is observed
     * without waiting out a timeout.
     */
    private void publishSentinel(String topic, Map<String, Object> payload) {
        messagingTemplate.convertAndSend(topic, payload, Map.of());
    }

    private StompFrameHandler recordingFrameHandler(
            List<String> frames, CompletableFuture<Void> sentinelArrived) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                String frame = new String((byte[]) payload, StandardCharsets.UTF_8);
                frames.add(frame);
                if (frame.contains(SENTINEL)) {
                    sentinelArrived.complete(null);
                }
            }
        };
    }

    private StompFrameHandler byteFrameHandler(CompletableFuture<byte[]> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.complete((byte[]) payload);
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

    private User activeUser(String label) {
        String username = "bsg_" + label + "_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName("Broker Send Guard " + label)
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }

    @TestConfiguration
    static class HandledFrameProbeConfig implements WebSocketMessageBrokerConfigurer {

        private final HandledFrameProbe probe = new HandledFrameProbe();

        @Bean
        HandledFrameProbe handledFrameProbe() {
            return probe;
        }

        @Override
        public void configureClientInboundChannel(ChannelRegistration registration) {
            registration.interceptors(probe);
        }
    }

    /**
     * Observes the inbound channel without altering it, completing a future once a given handler
     * has finished processing a frame: the simple broker acknowledges neither a SUBSCRIBE nor a
     * SEND to an application destination on the wire.
     */
    static final class HandledFrameProbe implements ExecutorChannelInterceptor {

        private final Queue<Expectation> expectations = new ConcurrentLinkedQueue<>();

        CompletableFuture<Void> expect(
                StompCommand command,
                String destination,
                Class<? extends MessageHandler> handlerType) {
            Expectation expectation =
                    new Expectation(command, destination, handlerType, new CompletableFuture<>());
            expectations.add(expectation);
            return expectation.handled();
        }

        @Override
        public void afterMessageHandled(
                Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
            for (Expectation expectation : expectations) {
                if (expectation.matches(accessor, handler) && expectations.remove(expectation)) {
                    if (ex == null) {
                        expectation.handled().complete(null);
                    } else {
                        expectation.handled().completeExceptionally(ex);
                    }
                }
            }
        }

        private record Expectation(
                StompCommand command,
                String destination,
                Class<? extends MessageHandler> handlerType,
                CompletableFuture<Void> handled) {

            boolean matches(StompHeaderAccessor accessor, MessageHandler handler) {
                return command == accessor.getCommand()
                        && destination.equals(accessor.getDestination())
                        && handlerType.isInstance(handler);
            }
        }
    }
}
