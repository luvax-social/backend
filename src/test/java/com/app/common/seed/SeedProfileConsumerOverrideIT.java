package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.modules.auth.messaging.AuthMailEventConsumer;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

/**
 * Proves {@code application-seed.yml} actually overrides {@code application-dev.yml}'s consumer
 * flags when both profiles are active together, rather than trusting Spring's documented
 * last-active-profile-wins rule on faith.
 *
 * <p>This matters because {@code application-dev.yml} sets every {@code app.*.consumer.enabled}
 * property as a plain literal boolean, not an env-var-driven placeholder — Task 9 proved
 * empirically that an environment variable of the same name has no effect against a plain literal.
 * The only remaining unverified assumption was whether a second, later-listed profile's YAML file
 * actually overrides a plain literal from an earlier-listed profile's YAML file for the same key.
 * Spring Boot's reference documentation states property sources contributed by profile-specific
 * documents are ordered according to their profile's position in {@code spring.profiles.active},
 * with later-listed profiles taking precedence
 * (https://docs.spring.io/spring-boot/reference/features/external-config.html, "Profile Specific
 * Files" — "profiles are applied in the order in which they are defined") — this test activates
 * {@code dev,seed} exactly as {@code SeedRunner} (Task 8) will, and asserts the merge behaves that
 * way against the real files on the classpath instead of only against documentation.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class SeedProfileConsumerOverrideIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "seed-profile-override-it-secret-32-characters!!");
        registry.add("JWT_ISSUER", () -> "https://seedprofileoverride.it.local");
        registry.add("JWT_AUDIENCE", () -> "App");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        registry.add("app.outbox.publisher.enabled", () -> false);
        registry.add("app.hashtag.seed.enabled", () -> false);
        registry.add("app.post.seed.enabled", () -> false);
        registry.add("app.stats.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private Environment environment;

    @Autowired private ApplicationContext applicationContext;

    // application-seed.yml is the only thing preventing a seed replay from dispatching real
    // provider mail to fabricated seeded addresses now that the local Mailpit sink is gone -
    // asserting the resolved property is false (below) shows the config the guard depends on
    // is correct, but not that the guard actually wires no listener for it. This asserts the
    // AuthMailEventConsumer bean itself is absent, which is what actually stops mail.queue from
    // ever being consumed during a seed run.
    @Test
    void seedProfile_mailConsumerBeanIsAbsent() {
        assertThat(applicationContext.getBeanNamesForType(AuthMailEventConsumer.class)).isEmpty();
    }

    @Test
    void seedProfile_overridesFiveNotificationProducingConsumers_toFalse() {
        assertThat(environment.getProperty("app.mail.consumer.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.notification.consumer.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("app.comment.consumer.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("app.story.consumer.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.admin.consumer.enabled", Boolean.class)).isFalse();
    }

    @Test
    void seedProfile_leavesIndexAndRecommendationConsumers_true() {
        assertThat(environment.getProperty("app.hashtag.consumer.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.post.consumer.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.recommendation.consumer.enabled", Boolean.class))
                .isTrue();
    }
}
