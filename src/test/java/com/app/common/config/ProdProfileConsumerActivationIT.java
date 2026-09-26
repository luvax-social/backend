package com.app.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.comment.consumer.CommentNotificationConsumer;
import com.app.modules.mail.service.impl.AbstractTemplateMailSender;
import com.app.modules.story.consumer.StoryNotificationConsumer;
import com.app.testsupport.TestContainerImages;

@SpringBootTest(
        properties = {
            "spring.profiles.active=prod",
            "spring.docker.compose.enabled=false",
            "logging.file.path=target/prod-profile-consumer-activation-it-logs",
            "app.outbox.publisher.enabled=false",
            "app.hashtag.seed.enabled=false",
            "app.post.seed.enabled=false",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true",
            // Surefire pins APP_MAIL_TRANSPORT=noop for the whole suite so no test reaches the
            // real provider. This is the one prod-profile context, and MailTransportGuard refuses
            // noop outside dev, so it has to name its own transport. It previously inherited
            // application-prod.yml's literal transport: resend, which shadowed the pin by
            // accident; that literal is gone now that the variable is the configured input, so
            // the requirement is stated here instead. Nothing is sent: RESEND_API_KEY below is a
            // dummy and the outbox publisher is disabled, so no mail path runs.
            "app.mail.transport=resend"
        })
@Testcontainers
class ProdProfileConsumerActivationIT {

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
        // RequiredEnvironmentGuard is @Profile("prod") and this is the only prod-profile test, so
        // it is the only place that bean runs. @ServiceConnection wires the real DataSource
        // through a JdbcConnectionDetails bean, but leaves spring.datasource.url/username/password
        // bound to application.yaml's own ${POSTGRES_URL}/${POSTGRES_USER}/${POSTGRES_PASSWORD}
        // placeholders, which is exactly what the guard scans for. These three satisfy the scan;
        // they do not change which DataSource the context actually builds.
        r.add("POSTGRES_URL", () -> postgres.getJdbcUrl());
        r.add("POSTGRES_USER", postgres::getUsername);
        r.add("POSTGRES_PASSWORD", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> "prod-profile-consumer-it-secret-32-chars-min!!");
        // Supplied explicitly because this is the only prod-profile test: application-prod.yml
        // does not override the secret, so without this the binding falls through to the raw
        // ${APP_COOKIE_SIGNING_SECRET} placeholder text, which fails the 32-character floor.
        r.add(
                "APP_COOKIE_SIGNING_SECRET",
                () -> "prod-profile-consumer-it-cookie-signing-secret-32-min");
        r.add("JWT_ISSUER", () -> "https://prod-profile-consumer-it.test.local");
        r.add("JWT_AUDIENCE", () -> "prod-profile-consumer-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Prod Profile Consumer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private ApplicationContext applicationContext;

    // Overridden at AbstractTemplateMailSender, not at the MailSender interface. Under this
    // profile the transport is resend, so resendMailSender is the only candidate, and both
    // ModerationMailEventHandler injects the abstract class rather than the interface. A mock
    // typed as the interface replaces the same bean with something it cannot accept, and the
    // context fails to load before any assertion here runs.
    @MockitoBean private AbstractTemplateMailSender resendMailSender;

    @Test
    void prodProfile_activatesCommentAndStoryNotificationConsumerBeans() {
        assertThat(applicationContext.getBeanNamesForType(CommentNotificationConsumer.class))
                .as(
                        "CommentNotificationConsumer must be registered when spring.profiles.active=prod")
                .isNotEmpty();
        assertThat(applicationContext.getBeanNamesForType(StoryNotificationConsumer.class))
                .as("StoryNotificationConsumer must be registered when spring.profiles.active=prod")
                .isNotEmpty();
    }
}
