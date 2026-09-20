package com.app.modules.comment.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
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

import com.app.common.security.jwt.JwtTokenProvider;
import com.app.modules.auth.service.WebSocketTicketService;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.post.entity.Post;
import com.app.modules.post.enums.PostStatus;
import com.app.modules.post.enums.PostType;
import com.app.modules.post.repository.PostRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

/**
 * Proves the WebSocket comment fan-out honors the stealth block model end to end: a subscriber in a
 * block relationship with a commenter must not receive that commenter's live event over {@code
 * /topic/comments.{postId}.events}, while comments from every unrelated party still arrive with no
 * added delay.
 *
 * <p>Both block directions are exercised since {@link
 * com.app.modules.social.repository.BlockRepository#findBlockedCounterpartyIds} is bidirectional
 * and a one-directional test would not catch a regression that only filters the direction it
 * happens to check.
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
            "app.outbox.publisher.enabled=true",
            "app.outbox.publisher.initial-delay=PT0S",
            "app.outbox.publisher.fixed-delay=PT0.3S",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class CommentLiveBlockFilterIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
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
        r.add("JWT_SECRET", () -> "comment-live-block-filter-it-secret-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://comment-live-block-filter.it.local");
        r.add("JWT_AUDIENCE", () -> "comment-live-block-filter-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Comment Live Block Filter IT");
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
    @Autowired private JdbcTemplate jdbcTemplate;
    // The handshake accepts a single-use ticket, not a raw access token, so a test that opens a
    // real socket mints one the same way the client does.
    @Autowired private WebSocketTicketService webSocketTicketService;

    // Declared at the concrete type rather than at the MailSender interface, because
    // ModerationMailEventHandler injects AbstractTemplateMailSender rather than the
    // interface. An interface-typed override replaces the transport bean with a
    // proxy that is not assignable to it, and the context then fails to start before any
    // assertion runs.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private WebSocketStompClient stompClient;
    private StompSession viewerSession;
    private User postOwner;
    private User viewer;
    private User blockedCommenter;
    private User unblockedCommenter;
    private Post post;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        postOwner = insertUser("owner");
        viewer = insertUser("viewer");
        blockedCommenter = insertUser("blocked");
        unblockedCommenter = insertUser("unrelated");
        post =
                postRepository.save(
                        Post.builder()
                                .userId(postOwner.getId())
                                .postType(PostType.IMAGE)
                                .status(PostStatus.PUBLISHED)
                                .caption("comment live block filter it post")
                                .build());
    }

    @AfterEach
    void tearDown() {
        if (viewerSession != null && viewerSession.isConnected()) {
            viewerSession.disconnect();
        }
        jdbcTemplate.update("DELETE FROM blocks");
    }

    @Test
    void viewerBlocksCommenter_commentersEventNeverArrives_unrelatedPartysDoes() throws Exception {
        block(viewer.getId(), blockedCommenter.getId());
        assertBlockedCommenterFilteredAndUnrelatedDelivered();
    }

    @Test
    void commenterBlocksViewer_reverseDirectionAlsoFiltered() throws Exception {
        block(blockedCommenter.getId(), viewer.getId());
        assertBlockedCommenterFilteredAndUnrelatedDelivered();
    }

    private void assertBlockedCommenterFilteredAndUnrelatedDelivered() throws Exception {
        String viewerToken = jwtTokenProvider.generateAccessToken(viewer.getId(), "USER", 0);
        String blockedCommenterToken =
                jwtTokenProvider.generateAccessToken(blockedCommenter.getId(), "USER", 0);
        String unblockedCommenterToken =
                jwtTokenProvider.generateAccessToken(unblockedCommenter.getId(), "USER", 0);
        String wsUrl =
                "ws://localhost:"
                        + port
                        + "/ws/comments/websocket?ticket="
                        + webSocketTicketService.issueTicket(viewerToken);

        LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        viewerSession =
                stompClient
                        .connectAsync(wsUrl, new StompSessionHandlerAdapter() {})
                        .get(15, TimeUnit.SECONDS);
        assertThat(viewerSession.isConnected()).isTrue();
        viewerSession.subscribe(
                "/topic/comments." + post.getId() + ".events",
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        frames.add(new String((byte[]) payload, StandardCharsets.UTF_8));
                    }
                });

        createCommentOverHttp(blockedCommenterToken, "must never reach the blocked viewer");
        createCommentOverHttp(unblockedCommenterToken, "must reach the viewer normally");

        String firstFrame = frames.poll(20, TimeUnit.SECONDS);
        assertThat(firstFrame)
                .as("only the unrelated commenter's event should ever arrive")
                .isNotNull()
                .contains("must reach the viewer normally")
                .doesNotContain("must never reach the blocked viewer");

        String secondFrame = frames.poll(3, TimeUnit.SECONDS);
        assertThat(secondFrame)
                .as("the blocked commenter's event must be dropped, not merely delayed")
                .isNull();
    }

    private void createCommentOverHttp(String token, String content) {
        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("postId", post.getId().toString(), "content", content);
        rest.exchange(
                "http://localhost:" + port + "/api/v1/posts/" + post.getId() + "/comments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private void block(UUID blockerId, UUID blockedId) {
        jdbcTemplate.update(
                "INSERT INTO blocks(blocker_id, blocked_id, created_at) VALUES (?, ?, ?)",
                blockerId,
                blockedId,
                OffsetDateTime.now());
    }

    private User insertUser(String label) {
        String username = "ws_" + label + "_" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(
                User.builder()
                        .username(username)
                        .email(username + "@test.local")
                        .displayName(label)
                        .role(UserRole.USER)
                        .status(UserStatus.ACTIVE)
                        .isPrivate(false)
                        .isVerified(false)
                        .build());
    }
}
