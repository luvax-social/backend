package com.app.modules.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.notification.entity.Notification;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationRepository;
import com.app.modules.social.messaging.SocialEventTypes;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;
import com.rabbitmq.client.Channel;

@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.notification.consumer.enabled=true",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class SocialNotificationConsumerIT {

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
        r.add("JWT_SECRET", () -> "social-consumer-it-secret-32-chars-minimum-len!!");
        r.add("JWT_ISSUER", () -> "https://social-consumer-it.test.local");
        r.add("JWT_AUDIENCE", () -> "social-consumer-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Social Consumer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private SocialNotificationConsumer consumer;
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
    private User recipient;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        actor =
                userRepository.save(
                        activeUser("actor_" + UUID.randomUUID().toString().substring(0, 8)));
        recipient =
                userRepository.save(
                        activeUser("recip_" + UUID.randomUUID().toString().substring(0, 8)));
    }

    @Test
    void handle_followEvent_createsNotification() throws Exception {
        Channel channel = mock(Channel.class);
        follow(actor.getId(), recipient.getId(), "accepted");
        Message message =
                envelopeMessage(
                        UUID.randomUUID(),
                        SocialEventTypes.USER_FOLLOWED_V1,
                        actor.getId(),
                        recipient.getId());

        consumer.consume(message, channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        Notification created = rows.get(0);
        assertThat(created.getType()).isEqualTo(NotificationType.FOLLOW);
        assertThat(created.getActorId()).isEqualTo(actor.getId());
        assertThat(created.getRecipientId()).isEqualTo(recipient.getId());
        verify(channel).basicAck(0L, false);
    }

    @Test
    void handle_followRequestEvent_createsNotification() throws Exception {
        Channel channel = mock(Channel.class);
        follow(actor.getId(), recipient.getId(), "pending");
        Message message =
                envelopeMessage(
                        UUID.randomUUID(),
                        SocialEventTypes.USER_FOLLOW_REQUESTED_V1,
                        actor.getId(),
                        recipient.getId());

        consumer.consume(message, channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.FOLLOW_REQUEST);
        verify(channel).basicAck(0L, false);
    }

    @Test
    void handle_duplicateEventId_doesNotCreateSecondRow() throws Exception {
        Channel channel = mock(Channel.class);
        follow(actor.getId(), recipient.getId(), "accepted");
        UUID eventId = UUID.randomUUID();
        Message first =
                envelopeMessage(
                        eventId,
                        SocialEventTypes.USER_FOLLOWED_V1,
                        actor.getId(),
                        recipient.getId());
        Message second =
                envelopeMessage(
                        eventId,
                        SocialEventTypes.USER_FOLLOWED_V1,
                        actor.getId(),
                        recipient.getId());

        consumer.consume(first, channel);
        consumer.consume(second, channel);

        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    @Test
    void handle_unfollowAfterTheEdgeIsGone_removesTheFollowNotification() throws Exception {
        Channel channel = mock(Channel.class);
        follow(actor.getId(), recipient.getId(), "accepted");
        consumer.consume(
                envelopeMessage(
                        UUID.randomUUID(),
                        SocialEventTypes.USER_FOLLOWED_V1,
                        actor.getId(),
                        recipient.getId()),
                channel);
        jdbcTemplate.update(
                "DELETE FROM follows WHERE follower_id = ? AND following_id = ?",
                actor.getId(),
                recipient.getId());

        consumer.consume(
                MessageBuilder.withBody(
                                DomainEventEnvelopeJson.write(
                                                new DomainEventEnvelope(
                                                        UUID.randomUUID(),
                                                        SocialEventTypes.USER_UNFOLLOWED_V1,
                                                        OffsetDateTime.now(ZoneOffset.UTC),
                                                        actor.getId(),
                                                        "user",
                                                        recipient.getId(),
                                                        Map.of(
                                                                "followerId",
                                                                actor.getId().toString(),
                                                                "followingId",
                                                                recipient.getId().toString(),
                                                                "previousStatus",
                                                                "accepted")))
                                        .getBytes(StandardCharsets.UTF_8))
                        .setDeliveryTag(0L)
                        .build(),
                channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDeletedAt()).isNotNull();
    }

    @Test
    void handle_approvedRequest_convertsTheRequestInPlace() throws Exception {
        Channel channel = mock(Channel.class);
        follow(actor.getId(), recipient.getId(), "pending");
        consumer.consume(
                envelopeMessage(
                        UUID.randomUUID(),
                        SocialEventTypes.USER_FOLLOW_REQUESTED_V1,
                        actor.getId(),
                        recipient.getId()),
                channel);
        UUID requestId = notificationRepository.findAll().get(0).getId();
        jdbcTemplate.update(
                "UPDATE follows SET status = 'accepted' WHERE follower_id = ? AND following_id = ?",
                actor.getId(),
                recipient.getId());

        consumer.consume(
                MessageBuilder.withBody(
                                DomainEventEnvelopeJson.write(
                                                new DomainEventEnvelope(
                                                        UUID.randomUUID(),
                                                        SocialEventTypes
                                                                .USER_FOLLOW_REQUEST_APPROVED_V1,
                                                        OffsetDateTime.now(ZoneOffset.UTC),
                                                        recipient.getId(),
                                                        "user",
                                                        recipient.getId(),
                                                        Map.of(
                                                                "requesterId",
                                                                actor.getId().toString(),
                                                                "approverId",
                                                                recipient.getId().toString())))
                                        .getBytes(StandardCharsets.UTF_8))
                        .setDeliveryTag(0L)
                        .build(),
                channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getId()).isEqualTo(requestId);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.FOLLOW);
    }

    @Test
    void handle_malformedPayload_routesToDlq() throws Exception {
        Channel channel = mock(Channel.class);
        Message badMessage =
                MessageBuilder.withBody("{bad json".getBytes(StandardCharsets.UTF_8))
                        .setDeliveryTag(0L)
                        .build();

        consumer.consume(badMessage, channel);

        assertThat(notificationRepository.findAll()).isEmpty();
        verify(channel).basicAck(0L, false);
    }

    @Test
    void handle_selfFollowEvent_doesNotCreateNotification() throws Exception {
        Channel channel = mock(Channel.class);
        Message message =
                envelopeMessage(
                        UUID.randomUUID(),
                        SocialEventTypes.USER_FOLLOWED_V1,
                        actor.getId(),
                        actor.getId());

        consumer.consume(message, channel);

        assertThat(notificationRepository.findAll()).isEmpty();
        verify(channel).basicAck(0L, false);
    }

    @Test
    void handle_unknownEventType_acksAndSkips() throws Exception {
        Channel channel = mock(Channel.class);
        Message message =
                envelopeMessage(
                        UUID.randomUUID(), "unknown.event.v1", actor.getId(), recipient.getId());

        consumer.consume(message, channel);

        assertThat(notificationRepository.findAll()).isEmpty();
        verify(channel).basicAck(0L, false);
    }

    private static Message envelopeMessage(
            UUID eventId, String eventType, UUID actorId, UUID aggregateId) {
        DomainEventEnvelope env =
                new DomainEventEnvelope(
                        eventId,
                        eventType,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        actorId,
                        "user",
                        aggregateId,
                        Map.of(
                                "followerId",
                                actorId.toString(),
                                "followingId",
                                aggregateId.toString()));
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(env).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(0L)
                .build();
    }

    private void follow(UUID follower, UUID following, String status) {
        jdbcTemplate.update(
                "INSERT INTO follows (follower_id, following_id, status)"
                        + " VALUES (?, ?, CAST(? AS follow_status))",
                follower,
                following,
                status);
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
}
