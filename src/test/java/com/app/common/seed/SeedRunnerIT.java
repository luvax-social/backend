package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
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

import com.app.modules.mail.service.MailService;

/**
 * Proves {@link SeedRunner#assertEnumCoverage()} actually throws, naming the exact shortfall,
 * against a deliberately-thin fixture — rather than trusting the assertion logic on faith.
 *
 * <p>{@code SeedRunner} itself is never obtained as a Spring bean here: {@code SEED_DATA} is left
 * unset, so the {@code @ConditionalOnProperty} gate keeps it out of the context entirely, which
 * means the {@code @EventListener(ApplicationReadyEvent.class)} hook never fires and this test
 * never risks a real writer-chain seed run racing its own thin fixture. Instead, a {@code
 * SeedRunner} instance is constructed directly with the real {@link JdbcTemplate} this context
 * wires against the migrated Testcontainers schema — every other constructor argument is unused by
 * {@link SeedRunner#assertEnumCoverage()} and is passed as {@code null}.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class SeedRunnerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("JWT_SECRET", () -> "seed-runner-it-secret-key-32-characters!!");
        registry.add("JWT_ISSUER", () -> "https://seedrunner.it.local");
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

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void assertEnumCoverage_thinHashtagStatusFixture_throwsNamingTheShortfall() {
        insertHashtags("active", 5);
        insertHashtags("banned", 5);
        // Deliberately thin: only 2 rows, one short of MIN_ROWS_PER_ENUM_VALUE (5).
        insertHashtags("deleted", 2);

        SeedRunner seedRunner =
                new SeedRunner(
                        jdbcTemplate,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThatThrownBy(seedRunner::assertEnumCoverage)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hashtags.status='deleted'")
                .hasMessageContaining("has 2 row(s)")
                .hasMessageContaining("needs >= 5");
    }

    private void insertHashtags(String status, int count) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO hashtags (id, name, status) VALUES (?, ?, ?::hashtag_status)",
                    UUID.randomUUID(),
                    status + "_" + UUID.randomUUID(),
                    status);
        }
    }
}
