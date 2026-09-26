package com.app.common.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.testsupport.TestContainerImages;

/**
 * Proves the broker-wide send guard is registered when messaging is the only live endpoint enabled.
 *
 * <p>{@link BrokerSendGuardIT} sibling coverage exercises the guard's behaviour with the comment
 * and notification endpoints on. This class exercises the condition that decides whether the guard
 * exists at all, which is a separate failure mode: {@link
 * com.app.common.config.websocket.WebSocketBrokerConfig} owns both the guard and the
 * session-tracking decorator, and its activation expression originally covered only the comment,
 * notification, and post flags. Enabling messaging alone therefore produced a STOMP broker with no
 * guard on the inbound channel, so a client could address a SEND frame straight at a {@code
 * /topic/**} destination and every subscriber would receive it as though the server had published
 * it. Disabling notification live delivery, an unrelated operational decision, was enough to remove
 * a security control from the messaging subsystem.
 *
 * <p>Asserting on the registered interceptors rather than on a forged SEND round trip keeps this
 * test about registration and leaves the guard's rejection logic to the sibling class.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            // Messaging is the only live endpoint. Every other live flag is explicitly off, which
            // is the combination that used to leave the inbound channel unguarded.
            "app.message.live.enabled=true",
            "app.comment.live.enabled=false",
            "app.notification.live.enabled=false",
            "app.post.live.enabled=false",
            "app.message.consumer.enabled=false",
            "app.comment.consumer.enabled=false",
            "app.notification.consumer.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "app.outbox.publisher.enabled=false"
        })
@Testcontainers
class BrokerSendGuardRegistrationIT {

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
        r.add("JWT_SECRET", () -> "send-guard-registration-it-secret-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://send-guard-registration-it.test.local");
        r.add("JWT_AUDIENCE", () -> "send-guard-registration-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Send Guard Registration IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired
    @Qualifier("clientInboundChannel")
    private AbstractSubscribableChannel clientInboundChannel;

    @Test
    void sendGuard_isRegisteredWhenMessagingIsTheOnlyLiveEndpoint() {
        assertThat(clientInboundChannel.getInterceptors())
                .as(
                        "a forged client SEND at /topic/** is only rejected if this guard is"
                                + " registered")
                .hasAtLeastOneElementOfType(BrokerSendGuardInterceptor.class);
    }
}
