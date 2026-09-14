package com.app.modules.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.users.entity.User;
import com.app.modules.users.enums.UserRole;
import com.app.modules.users.enums.UserStatus;
import com.app.modules.users.repository.UserRepository;

@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.mail.consumer.enabled=true",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class AuthMailEventConsumerRabbitMqIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
                    .withExposedPorts(5672);

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private UserRepository userRepository;

    // Declared at the concrete type rather than at the MailSender interface, because
    // ModerationMailEventHandler and MailCampaignSenderJob both inject
    // AbstractTemplateMailSender. An interface-typed override replaces the transport bean with a
    // proxy that is not assignable to it, and the context then fails to start before any
    // assertion runs.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("JWT_SECRET", () -> "rabbit-consumer-it-secret-32-chars-minimum-len!!");
        registry.add("JWT_ISSUER", () -> "https://rabbit-consumer-it.test.local");
        registry.add("JWT_AUDIENCE", () -> "rabbit-consumer-it");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App Rabbit IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @BeforeEach
    void purgeQueues() {
        rabbitTemplate.execute(
                channel -> {
                    channel.queuePurge(RabbitMqTopologyConfig.MAIL_QUEUE);
                    channel.queuePurge(RabbitMqTopologyConfig.MAIL_DEAD_LETTER_QUEUE);
                    return null;
                });
    }

    @Test
    void consumerProcessesUserRegisteredEventAndAcksMessage() {
        User user = userRepository.save(activeUser());
        publish(event(AuthEventTypes.USER_REGISTERED_V1, user.getId()));

        verify(mailSender, timeout(5000)).sendWelcome("rabbit-it@example.com", "Rabbit IT");

        Message remaining = rabbitTemplate.receive(RabbitMqTopologyConfig.MAIL_QUEUE, 100);
        assertThat(remaining).isNull();
    }

    @Test
    void consumerRoutesInvalidPayloadToDeadLetterQueue() {
        rabbitTemplate.convertAndSend(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                AuthEventTypes.USER_REGISTERED_V1,
                "{bad json");

        Message deadLetter =
                rabbitTemplate.receive(RabbitMqTopologyConfig.MAIL_DEAD_LETTER_QUEUE, 5000);

        assertThat(deadLetter).isNotNull();
        assertThat(new String(deadLetter.getBody(), StandardCharsets.UTF_8)).isEqualTo("{bad json");
    }

    private void publish(DomainEventEnvelope event) {
        rabbitTemplate.convertAndSend(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                event.eventType(),
                DomainEventEnvelopeJson.write(event));
    }

    private DomainEventEnvelope event(String eventType, UUID userId) {
        return new DomainEventEnvelope(
                UUID.randomUUID(),
                eventType,
                OffsetDateTime.now(ZoneOffset.UTC),
                userId,
                "user",
                userId,
                Map.of("userId", userId.toString()));
    }

    private User activeUser() {
        return User.builder()
                .username("rabbit_it")
                .email("rabbit-it@example.com")
                .displayName("Rabbit IT")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .isPrivate(false)
                .isVerified(false)
                .build();
    }
}
