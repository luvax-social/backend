package com.app.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
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

import com.app.common.seed.loader.SeedContent;
import com.app.common.seed.loader.SeedDataLoader;
import com.app.common.seed.reset.SeedResetService;
import com.app.common.seed.time.SeedTimeline;
import com.app.common.seed.writer.UserSeedWriter;
import com.app.modules.mail.service.MailService;

/**
 * Proves {@link UserSeedWriter#write(SeedContent, SeedTimeline)} writes the full 90-row {@code
 * users.json} fixture into {@code users}/{@code user_credentials}/{@code user_settings} against a
 * real Flyway-migrated schema.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev,seed",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"
        })
@Testcontainers
class UserSeedWriterIT {

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
        registry.add("JWT_SECRET", () -> "user-seed-writer-it-secret-at-least-32-chars!!");
        registry.add("JWT_ISSUER", () -> "https://userseedwriter.it.local");
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
    @Autowired private UserSeedWriter userSeedWriter;
    @Autowired private SeedResetService seedResetService;

    @Test
    void write_seedsEveryUserWithCredentialsAndSettings() {
        seedResetService.reset();
        SeedContent content = new SeedDataLoader().load();
        SeedTimeline timeline = new SeedTimeline(20260825L, Instant.parse("2026-08-25T00:00:00Z"));

        Map<String, UUID> usersByUsername = userSeedWriter.write(content, timeline);

        assertThat(usersByUsername).hasSize(140);
        assertThat(countRows("users")).isEqualTo(140);
        assertThat(countRows("user_credentials")).isEqualTo(140);
        assertThat(countRows("user_settings")).isEqualTo(140);

        assertThat(usersByUsername).containsKey("user_power");
        Integer roleCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE username = 'user_power' AND role = 'user'",
                        Integer.class);
        assertThat(roleCount).isEqualTo(1);

        // follower_count/following_count/post_count are trigger-maintained and must stay at their
        // DEFAULT 0 — the writer must never set them directly.
        Integer nonZeroCounters =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE follower_count <> 0 OR following_count"
                                + " <> 0 OR post_count <> 0",
                        Integer.class);
        assertThat(nonZeroCounters).isZero();
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
