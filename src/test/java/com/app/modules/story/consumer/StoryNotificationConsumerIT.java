package com.app.modules.story.consumer;

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
import com.app.modules.story.messaging.StoryEventTypes;
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
            "app.story.consumer.enabled=true",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class StoryNotificationConsumerIT {

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
        r.add("JWT_SECRET", () -> "story-consumer-it-secret-32-chars-minimum!!!!");
        r.add("JWT_ISSUER", () -> "https://story-consumer-it.test.local");
        r.add("JWT_AUDIENCE", () -> "story-consumer-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Story Consumer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private StoryNotificationConsumer consumer;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;

    // Declared at the concrete type rather than at the MailSender interface, because
    // ModerationMailEventHandler injects AbstractTemplateMailSender rather than the
    // interface. An interface-typed override replaces the transport bean with a
    // proxy that is not assignable to it, and the context then fails to start before any
    // assertion runs.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private User viewer;
    private User owner;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        viewer = userRepository.save(activeUser("viewer_" + suffix()));
        owner = userRepository.save(activeUser("owner_" + suffix()));
    }

    @Test
    void handle_storyViewed_createsStoryViewNotification() throws Exception {
        Channel channel = mock(Channel.class);
        UUID storyId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("storyId", storyId.toString());
        data.put("ownerId", owner.getId().toString());

        consumer.consume(message(UUID.randomUUID(), viewer.getId(), storyId, data), channel);

        List<Notification> rows = notificationRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo(NotificationType.STORY_VIEW);
        assertThat(rows.get(0).getRecipientId()).isEqualTo(owner.getId());
        assertThat(rows.get(0).getActorId()).isEqualTo(viewer.getId());
    }

    @Test
    void handle_selfView_doesNotCreateNotification() throws Exception {
        Channel channel = mock(Channel.class);
        UUID storyId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("storyId", storyId.toString());
        data.put("ownerId", viewer.getId().toString());

        consumer.consume(message(UUID.randomUUID(), viewer.getId(), storyId, data), channel);

        assertThat(notificationRepository.findAll()).isEmpty();
    }

    @Test
    void handle_duplicateEventId_doesNotCreateSecondRow() throws Exception {
        Channel channel = mock(Channel.class);
        UUID eventId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        Map<String, Object> data = new HashMap<>();
        data.put("storyId", storyId.toString());
        data.put("ownerId", owner.getId().toString());

        consumer.consume(message(eventId, viewer.getId(), storyId, data), channel);
        consumer.consume(message(eventId, viewer.getId(), storyId, data), channel);

        assertThat(notificationRepository.findAll()).hasSize(1);
    }

    private Message message(UUID eventId, UUID actorId, UUID storyId, Map<String, Object> data) {
        DomainEventEnvelope env =
                new DomainEventEnvelope(
                        eventId,
                        StoryEventTypes.STORY_VIEWED_V1,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        actorId,
                        "story",
                        storyId,
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
