package com.app.modules.post.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.app.testsupport.TestContainerImages;

/**
 * Proves a pass of the scheduled publisher against a real database: a due post is published once, a
 * post that is not due is left alone, and a due post whose place was banned returns to drafts with
 * a failure event instead of leaving the pass unable to commit.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class ScheduledPostPublisherIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "scheduled-post-publisher-it-secret-32-chars!!");
        r.add("JWT_ISSUER", () -> "https://scheduled-post-publisher.it.local");
        r.add("JWT_AUDIENCE", () -> "App");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @MockitoBean private MailService mailService;

    @Autowired private ScheduledPostPublisher publisher;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM posts");
        jdbcTemplate.update("DELETE FROM places");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void publishDue_duePost_isPublishedWithItsPublicationTime() {
        UUID owner = insertUser("sched_owner");
        UUID post = insertScheduledPost(owner, null, "-1 minute");

        int published = publisher.publishDue(10);

        assertThat(published).isOne();
        assertThat(statusOf(post)).isEqualTo("published");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT published_at IS NOT NULL AND scheduled_at IS NULL"
                                        + " FROM posts WHERE id = ?",
                                Boolean.class,
                                post))
                .isTrue();
        assertThat(countEvents("post.published.v1")).isOne();
    }

    @Test
    void publishDue_postNotYetDue_staysScheduled() {
        UUID owner = insertUser("sched_future");
        UUID post = insertScheduledPost(owner, null, "+1 hour");

        assertThat(publisher.publishDue(10)).isZero();

        assertThat(statusOf(post)).isEqualTo("scheduled");
        assertThat(countEvents("post.published.v1")).isZero();
    }

    @Test
    void publishDue_postWhosePlaceWasBanned_returnsToDraftsWithAFailureEvent() {
        UUID owner = insertUser("sched_banned_place");
        UUID place = insertPlace("Closed Quay", "banned");
        UUID post = insertScheduledPost(owner, place, "-1 minute");

        int published = publisher.publishDue(10);

        assertThat(published).isZero();
        assertThat(statusOf(post)).isEqualTo("draft");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT scheduled_at IS NULL FROM posts WHERE id = ?",
                                Boolean.class,
                                post))
                .isTrue();
        List<String> payloads =
                jdbcTemplate.queryForList(
                        "SELECT payload::text FROM outbox_events WHERE event_type ="
                                + " 'post.schedule-failed.v1'",
                        String.class);
        assertThat(payloads).hasSize(1);
        assertThat(payloads.get(0)).contains(post.toString()).contains("place_unavailable");
        assertThat(countEvents("post.published.v1")).isZero();
    }

    @Test
    void publishDue_failingPostDoesNotStopTheRestOfThePass() {
        UUID owner = insertUser("sched_mixed");
        UUID banned = insertPlace("Banned Pier", "banned");
        UUID failing = insertScheduledPost(owner, banned, "-3 minutes");
        UUID fine = insertScheduledPost(owner, null, "-1 minute");

        assertThat(publisher.publishDue(10)).isOne();

        assertThat(statusOf(failing)).isEqualTo("draft");
        assertThat(statusOf(fine)).isEqualTo("published");
    }

    private String statusOf(UUID post) {
        return jdbcTemplate.queryForObject(
                "SELECT CAST(status AS text) FROM posts WHERE id = ?", String.class, post);
    }

    private long countEvents(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ?", Long.class, eventType);
    }

    private UUID insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, NULL) RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username);
    }

    private UUID insertPlace(String name, String status) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO places(name, normalized_name, status) VALUES (?, ?,"
                        + " CAST(? AS place_status)) RETURNING id",
                UUID.class,
                name,
                name.toLowerCase(java.util.Locale.ROOT),
                status);
    }

    private UUID insertScheduledPost(UUID owner, UUID place, String dueOffset) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO posts(user_id, post_type, status, caption, place_id, scheduled_at)"
                        + " VALUES (?, 'text', 'scheduled', 'scheduled caption', ?,"
                        + " now() + CAST(? AS interval)) RETURNING id",
                UUID.class,
                owner,
                place,
                dueOffset);
    }
}
