package com.app.common.outbox.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

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
import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.enums.OutboxEventStatus;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.common.outbox.repository.OutboxEventRepository;
import com.app.common.outbox.service.OutboxPublisherService;
import com.app.modules.auth.messaging.AuthEventTypes;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.initial-delay=PT1H",
            "app.outbox.publisher.fixed-delay=PT1H",
            "app.outbox.publisher.confirm-timeout=PT5S",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true",
            "app.mail.consumer.enabled=false"
        })
@Testcontainers
class OutboxPublisherRabbitMqIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxPublisherService outboxPublisherService;
    @Autowired private RabbitTemplate rabbitTemplate;

    @MockitoBean private MailService mailService;

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("JWT_SECRET", () -> "rabbit-it-secret-32-chars-minimum-len!!");
        registry.add("JWT_ISSUER", () -> "https://rabbit-it.test.local");
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

    @Test
    void publishDueEvents_deliversRoutedMessageToMailQueueAndMarksPublished() {
        OutboxEvent event = insertEvent(AuthEventTypes.USER_REGISTERED_V1);

        int attempted = outboxPublisherService.publishDueEvents();

        Message message = rabbitTemplate.receive(RabbitMqTopologyConfig.MAIL_QUEUE, 5000);
        assertThat(message).isNotNull();
        OutboxEvent persisted = outboxEventRepository.findById(event.getId()).orElseThrow();
        DomainEventEnvelope delivered =
                DomainEventEnvelopeJson.read(new String(message.getBody(), StandardCharsets.UTF_8));

        assertThat(attempted).isOne();
        assertThat(delivered.eventId()).isEqualTo(event.getEventId());
        assertThat(message.getMessageProperties().getMessageId())
                .isEqualTo(event.getEventId().toString());
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo(AuthEventTypes.USER_REGISTERED_V1);
        assertThat(persisted.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(persisted.getPublishedAt()).isNotNull();
        assertThat(persisted.getLastError()).isNull();
    }

    @Test
    void publishDueEvents_unroutableMessageSchedulesRetry() {
        OutboxEvent event = insertEvent("unused.routing-key.v1");

        int attempted = outboxPublisherService.publishDueEvents();

        OutboxEvent persisted = outboxEventRepository.findById(event.getId()).orElseThrow();

        assertThat(attempted).isOne();
        assertThat(persisted.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(persisted.getAttemptCount()).isEqualTo(1);
        assertThat(persisted.getPublishedAt()).isNull();
        assertThat(persisted.getLastError()).contains("unroutable");
        assertThat(persisted.getNextRetryAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private OutboxEvent insertEvent(String routingKey) {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DomainEventEnvelope payload =
                new DomainEventEnvelope(
                        eventId,
                        AuthEventTypes.USER_REGISTERED_V1,
                        now,
                        aggregateId,
                        "user",
                        aggregateId,
                        Map.of("userId", aggregateId.toString()));
        return outboxEventRepository.insertPending(
                OutboxEvent.builder()
                        .eventId(eventId)
                        .aggregateType("user")
                        .aggregateId(aggregateId)
                        .eventType(AuthEventTypes.USER_REGISTERED_V1)
                        .routingKey(routingKey)
                        .payload(payload)
                        .status(OutboxEventStatus.PENDING)
                        .attemptCount(0)
                        .nextRetryAt(now.minusSeconds(1))
                        .build());
    }
}
