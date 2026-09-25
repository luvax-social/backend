package com.app.modules.comment.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.comment.messaging.CommentEventTypes;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationRepository;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.app.testsupport.TestContainerImages;
import com.rabbitmq.client.Channel;

@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.comment.consumer.enabled=true",
            "app.comment.live.enabled=false",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class CommentNotificationConsumerIT {

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
        r.add("JWT_SECRET", () -> "comment-consumer-it-secret-32-chars-minimum!!");
        r.add("JWT_ISSUER", () -> "https://comment-consumer-it.test.local");
        r.add("JWT_AUDIENCE", () -> "comment-consumer-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Comment Consumer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private CommentNotificationConsumer consumer;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    // Declared at the concrete type rather than at the MailSender interface, because
    // ModerationMailEventHandler injects AbstractTemplateMailSender rather than the
    // interface. An interface-typed override replaces the transport bean with a
    // proxy that is not assignable to it, and the context then fails to start before any
    // assertion runs.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private User actor;
    private User postOwner;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        actor = userRepository.save(activeUser("actor_" + suffix()));
        postOwner = userRepository.save(activeUser("owner_" + suffix()));
    }

    @Test
    void handle_commentCreatedTopLevel_createsCommentPostNotification() throws Exception {
        Channel channel = mock(Channel.class);
        UUID commentId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("commentId", commentId.toString());
        data.put("postOwnerId", postOwner.getId().toString());
        data.put("postId", postId.toString());
        data.put("depth", 0);

        consumer.consume(
                message(UUID.randomUUID(), CommentEventTypes.COMMENT_CREATED_V1, data), channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.COMMENT_POST);
        assertThat(rows.get(0).getRecipientId()).isEqualTo(postOwner.getId());
        assertThat(rows.get(0).getActorId()).isEqualTo(actor.getId());
        assertThat(rows.get(0).getPostId()).isEqualTo(postId);
    }

    @Test
    void handle_selfComment_doesNotCreateNotification() throws Exception {
        Channel channel = mock(Channel.class);
        Map<String, Object> data = new HashMap<>();
        data.put("commentId", UUID.randomUUID().toString());
        data.put("postOwnerId", actor.getId().toString());
        data.put("depth", 0);

        consumer.consume(
                message(UUID.randomUUID(), CommentEventTypes.COMMENT_CREATED_V1, data), channel);

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void handle_duplicateEventId_doesNotCreateSecondRow() throws Exception {
        Channel channel = mock(Channel.class);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("commentId", UUID.randomUUID().toString());
        data.put("postOwnerId", postOwner.getId().toString());
        data.put("depth", 0);

        consumer.consume(message(eventId, CommentEventTypes.COMMENT_CREATED_V1, data), channel);
        consumer.consume(message(eventId, CommentEventTypes.COMMENT_CREATED_V1, data), channel);

        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    @Test
    void handle_commentCreatedReply_createsReplyCommentNotification() throws Exception {
        Channel channel = mock(Channel.class);
        User parentOwner = userRepository.save(activeUser("parent_" + suffix()));
        UUID postId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("commentId", UUID.randomUUID().toString());
        data.put("postOwnerId", postOwner.getId().toString());
        data.put("parentOwnerId", parentOwner.getId().toString());
        data.put("postId", postId.toString());
        data.put("depth", 1);

        consumer.consume(
                message(UUID.randomUUID(), CommentEventTypes.COMMENT_CREATED_V1, data), channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.REPLY_COMMENT);
        assertThat(rows.get(0).getRecipientId()).isEqualTo(parentOwner.getId());
        assertThat(rows.get(0).getPostId()).isEqualTo(postId);
    }

    @Test
    void handle_commentLiked_createsLikeCommentNotification() throws Exception {
        Channel channel = mock(Channel.class);
        User commentOwner = userRepository.save(activeUser("liked_" + suffix()));
        // The consumer writes a like notification only while the like exists, so the like is
        // recorded first, as CommentServiceImpl does before it enqueues the event.
        UUID postId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO posts (user_id) VALUES (?) RETURNING id",
                        UUID.class,
                        commentOwner.getId());
        UUID commentId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO comments (post_id, user_id, content) VALUES (?, ?, 'hi')"
                                + " RETURNING id",
                        UUID.class,
                        postId,
                        commentOwner.getId());
        jdbcTemplate.update(
                "INSERT INTO comment_likes (user_id, comment_id) VALUES (?, ?)",
                actor.getId(),
                commentId);
        Map<String, Object> data = new HashMap<>();
        data.put("commentId", commentId.toString());
        data.put("commentOwnerId", commentOwner.getId().toString());
        data.put("postId", postId.toString());

        consumer.consume(
                message(UUID.randomUUID(), CommentEventTypes.COMMENT_LIKED_V1, data), channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.LIKE_COMMENT);
        assertThat(rows.get(0).getRecipientId()).isEqualTo(commentOwner.getId());
        assertThat(rows.get(0).getPostId()).isEqualTo(postId);
    }

    @Test
    void handle_selfLike_doesNotCreateNotification() throws Exception {
        Channel channel = mock(Channel.class);
        Map<String, Object> data = new HashMap<>();
        data.put("commentId", UUID.randomUUID().toString());
        data.put("commentOwnerId", actor.getId().toString());

        consumer.consume(
                message(UUID.randomUUID(), CommentEventTypes.COMMENT_LIKED_V1, data), channel);

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void handle_commentCreatedWithMention_createsMentionCommentNotification() throws Exception {
        Channel channel = mock(Channel.class);
        User mentioned = userRepository.save(activeUser("mention_" + suffix()));
        UUID postId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("commentId", UUID.randomUUID().toString());
        data.put("postOwnerId", postOwner.getId().toString());
        data.put("postId", postId.toString());
        data.put("depth", 0);
        data.put("mentionedUserIds", List.of(mentioned.getId().toString()));

        consumer.consume(
                message(UUID.randomUUID(), CommentEventTypes.COMMENT_CREATED_V1, data), channel);

        assertThat(notificationRepository.findAll())
                .anySatisfy(
                        n -> {
                            assertThat(n.getType()).isEqualTo(NotificationType.MENTION_COMMENT);
                            assertThat(n.getRecipientId()).isEqualTo(mentioned.getId());
                            assertThat(n.getPostId()).isEqualTo(postId);
                        });
    }

    private Message message(UUID eventId, String eventType, Map<String, Object> data) {
        DomainEventEnvelope env =
                new DomainEventEnvelope(
                        eventId,
                        eventType,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        actor.getId(),
                        "comment",
                        UUID.fromString(data.get("commentId").toString()),
                        data);
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(env).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(0L)
                .build();
    }

    private static User activeUser(String username) {
        return User.builder()
                .username(username)
                .email(username + "@test.local")
                .displayName(username)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .isPrivate(false)
                .isVerified(false)
                .build();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
