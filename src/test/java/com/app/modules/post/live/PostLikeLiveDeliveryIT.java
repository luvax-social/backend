package com.app.modules.post.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.outbox.service.OutboxService;
import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.auth.service.WebSocketTicketService;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.testsupport.TestContainerImages;

/**
 * Confirms a post like reaches a subscribed browser-equivalent client end to end through the
 * outbox, the RabbitMQ fanout tier, and the STOMP broker, carrying a count that matches the
 * database, and that a blocked viewer is filtered out of that fan-out per session.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.post.live.enabled=true",
            "app.comment.live.enabled=false",
            "app.comment.consumer.enabled=false",
            "app.post.consumer.enabled=false",
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
class PostLikeLiveDeliveryIT {

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
        r.add("JWT_SECRET", () -> "post-live-delivery-it-secret-32-chars-min!!!");
        r.add("JWT_ISSUER", () -> "https://post-live-delivery-it.test.local");
        r.add("JWT_AUDIENCE", () -> "post-live-delivery-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Post Live IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @LocalServerPort private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxService outboxService;

    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    // Declared at the concrete type rather than at the MailSender interface, because
    // ModerationMailEventHandler injects AbstractTemplateMailSender rather than the
    // interface. An interface-typed override replaces the transport bean with a
    // proxy that is not assignable to it, and the context then fails to start before any
    // assertion runs.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private WebSocketStompClient stompClient;
    private StompSession session;
    private UUID owner;
    private UUID viewer;
    private UUID post;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        owner = insertUser("post_owner_" + suffix());
        viewer = insertUser("post_viewer_" + suffix());
        post = insertPublishedPost(owner);
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM post_likes");
        jdbcTemplate.update("DELETE FROM blocks");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void subscriber_receivesALikeEventWhoseCountMatchesTheDatabase() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(viewer, post);

        likeOverHttp(viewer, post);

        assertThat(awaitFrameContaining(received, PostEventTypes.POST_LIVE_LIKED_V1))
                .contains("\"likeCount\":1");
        assertThat(likeCountInDatabase()).isEqualTo(1);
    }

    @Test
    void subscriber_receivesAnUnlikeEventCarryingTheDecrementedCount() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(viewer, post);
        likeOverHttp(viewer, post);
        awaitFrameContaining(received, PostEventTypes.POST_LIVE_LIKED_V1);

        unlikeOverHttp(viewer, post);

        assertThat(awaitFrameContaining(received, PostEventTypes.POST_LIVE_UNLIKED_V1))
                .contains("\"likeCount\":0");
        assertThat(likeCountInDatabase()).isZero();
    }

    @Test
    void selfLike_isDeliveredToTheOwnersOwnSubscription() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(owner, post);

        likeOverHttp(owner, post);

        assertThat(awaitFrameContaining(received, PostEventTypes.POST_LIVE_LIKED_V1))
                .contains("\"likeCount\":1");
    }

    @Test
    void viewerSubscribedBeforeTheBlock_stopsReceivingOnceTheOwnerBlocksThem() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(viewer, post);

        // The subscription predates the block, so subscribe-time authorisation cannot help here.
        // Only the outbound per-session filter can stop the frame.
        block(owner, viewer);
        likeOverHttp(owner, post);

        assertNoFrameArrives(received);
    }

    @Test
    void subscribingToAConstructedTopicString_isRejected() throws Exception {
        StompSession live = connectAs(viewer);
        session = live;
        BlockingQueue<byte[]> rejected = new LinkedBlockingQueue<>();

        // Deny-by-default within the post topic prefix: a destination that shares the prefix but
        // is not the full per-post pattern must be refused rather than passed through.
        live.subscribe("/topic/posts.not-a-uuid.events", byteHandler(rejected));

        likeOverHttp(viewer, post);

        assertNoFrameArrives(rejected);
    }

    @Test
    void anIndexSyncEvent_doesNotReachTheLiveTier() throws Exception {
        BlockingQueue<byte[]> received = subscribeAs(viewer, post);

        // post.index.* shares the social.events exchange with post.live.*. A post.# binding would
        // have delivered this into the live tier; the post.live.# binding must not.
        transactionTemplate.executeWithoutResult(
                status ->
                        outboxService.enqueue(
                                PostEventTypes.POST_INDEX_UPSERT_V1,
                                PostEventTypes.POST_INDEX_UPSERT_V1,
                                "post",
                                post,
                                owner,
                                java.util.Map.of("postId", post.toString())));

        assertNoFrameArrives(received);

        // The negative above would be vacuous if the channel were simply dead, so prove the same
        // subscription still delivers a genuine live event.
        likeOverHttp(viewer, post);
        assertThat(awaitFrameContaining(received, PostEventTypes.POST_LIVE_LIKED_V1))
                .contains("\"likeCount\":1");
    }

    private BlockingQueue<byte[]> subscribeAs(UUID userId, UUID postId) throws Exception {
        StompSession live = connectAs(userId);
        session = live;
        BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();
        live.subscribe("/topic/posts." + postId + ".events", byteHandler(received));
        return received;
    }

    // Waits for the first frame carrying the marker, discarding any earlier frame the same
    // subscription already received. BlockingQueue.poll blocks on a condition rather than
    // sleeping, so this does not reintroduce timed polling.
    private String awaitFrameContaining(BlockingQueue<byte[]> sink, String marker)
            throws InterruptedException {
        for (int attempt = 0; attempt < 8; attempt++) {
            byte[] frame = sink.poll(25, TimeUnit.SECONDS);
            if (frame == null) {
                break;
            }
            String body = new String(frame, StandardCharsets.UTF_8);
            if (body.contains(marker)) {
                return body;
            }
        }
        throw new AssertionError("no frame containing " + marker + " arrived");
    }

    private void assertNoFrameArrives(BlockingQueue<byte[]> sink) throws InterruptedException {
        assertThat(sink.poll(8, TimeUnit.SECONDS)).isNull();
    }

    private StompSession connectAs(UUID userId) throws Exception {
        String token = jwtTokenProvider.generateAccessToken(userId, "USER", 0);
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/posts/websocket?ticket="
                        + webSocketTicketService.issueTicket(token);
        StompSession live =
                stompClient
                        .connectAsync(wsUrl, new StompSessionHandlerAdapter() {})
                        .get(15, TimeUnit.SECONDS);
        assertThat(live.isConnected()).isTrue();
        return live;
    }

    private StompFrameHandler byteHandler(BlockingQueue<byte[]> sink) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                sink.add((byte[]) payload);
            }
        };
    }

    private void likeOverHttp(UUID userId, UUID postId) {
        exchange(userId, "/api/v1/posts/" + postId + "/like", HttpMethod.POST);
    }

    private void unlikeOverHttp(UUID userId, UUID postId) {
        exchange(userId, "/api/v1/posts/" + postId + "/like", HttpMethod.DELETE);
    }

    private void exchange(UUID userId, String path, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(userId, "USER", 0));
        new RestTemplate()
                .exchange(
                        "http://localhost:" + port + path,
                        method,
                        new HttpEntity<>(headers),
                        String.class);
    }

    private int likeCountInDatabase() {
        return jdbcTemplate.queryForObject(
                "SELECT like_count FROM posts WHERE id = ?", Integer.class, post);
    }

    private void block(UUID blocker, UUID blocked) {
        jdbcTemplate.update(
                "INSERT INTO blocks(blocker_id, blocked_id) VALUES (?, ?)", blocker, blocked);
    }

    private UUID insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@test.local",
                username);
    }

    private UUID insertPublishedPost(UUID userId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts(user_id, post_type, status) VALUES (?, 'image', 'published')"
                        + " RETURNING id",
                UUID.class,
                userId);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
