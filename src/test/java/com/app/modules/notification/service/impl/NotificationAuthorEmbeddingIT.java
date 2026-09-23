package com.app.modules.notification.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import com.app.common.response.CursorPageResponse;
import com.app.modules.mail.service.MailService;
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.service.NotificationService;
import com.app.modules.users.service.impl.UserSummaryServiceImpl;

/**
 * Proves the batched actor lookup added for O9: a page of notifications costs exactly one actor
 * query regardless of page size, and a soft-deleted actor renders the shared placeholder rather
 * than a raw id or a null field the client would have to branch on.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "spring.autoconfigure.exclude=org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "spring.jpa.properties.hibernate.generate_statistics=true",
            "app.notification.live.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.post.seed.enabled=false",
            "app.hashtag.seed.enabled=false"
        })
@Testcontainers
class NotificationAuthorEmbeddingIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("JWT_SECRET", () -> "notification-embedding-it-secret-32-chars-min!");
        r.add("JWT_ISSUER", () -> "https://notification.it.local");
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

    @Autowired private NotificationService notificationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM notifications");
        jdbcTemplate.update("DELETE FROM user_settings");
        jdbcTemplate.update("DELETE FROM users");
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void listNotifications_actorQueryCountIsConstantAcrossPageSize() {
        UUID recipient = insertUser("notif_recipient", false);
        for (int i = 0; i < 6; i++) {
            UUID actor = insertUser("notif_actor" + i, false);
            insertNotification(recipient, actor, minutesAgo(i));
        }

        Statistics stats = statistics();
        // Warm up so one-time metamodel/sequence statements do not skew the first measured call.
        notificationService.listNotifications(recipient, null, 2);

        stats.clear();
        notificationService.listNotifications(recipient, null, 2);
        long smallPage = stats.getPrepareStatementCount();

        stats.clear();
        notificationService.listNotifications(recipient, null, 6);
        long largePage = stats.getPrepareStatementCount();

        assertThat(largePage).isEqualTo(smallPage);
    }

    @Test
    void listNotifications_deletedActor_returnsPlaceholder() {
        UUID recipient = insertUser("notif_recipient2", false);
        UUID ghost = insertUser("notif_ghost", true);
        insertNotification(recipient, ghost, minutesAgo(1));

        CursorPageResponse<NotificationResponse> page =
                notificationService.listNotifications(recipient, null, 10);

        var actor = page.getContent().get(0).actor();
        assertThat(actor.id()).isEqualTo(ghost);
        assertThat(actor.username()).isNull();
        assertThat(actor.displayName()).isEqualTo(UserSummaryServiceImpl.DELETED_DISPLAY_NAME);
    }

    @Test
    void listNotifications_multipleActorsWithDuplicate_assignsEachRowsActor() {
        UUID recipient = insertUser("notif_recipient3", false);
        UUID alice = insertUser("notif_alice", false);
        UUID bob = insertUser("notif_bob", false);
        // Two rows from alice and one from bob, interleaved, to prove per-row assignment and dedup.
        insertNotification(recipient, alice, minutesAgo(3));
        insertNotification(recipient, bob, minutesAgo(2));
        insertNotification(recipient, alice, minutesAgo(1));

        List<NotificationResponse> content =
                notificationService.listNotifications(recipient, null, 10).getContent();

        assertThat(content).hasSize(3);
        // Newest first: alice, bob, alice.
        assertThat(content.get(0).actor().id()).isEqualTo(alice);
        assertThat(content.get(0).actor().username()).isEqualTo("notif_alice");
        assertThat(content.get(1).actor().id()).isEqualTo(bob);
        assertThat(content.get(2).actor().id()).isEqualTo(alice);
        assertThat(content.get(0).actor()).isEqualTo(content.get(2).actor());
    }

    @Test
    void listNotifications_systemNotificationWithoutActor_keepsMessage() {
        UUID recipient = insertUser("notif_recipient4", false);
        UUID notificationId =
                jdbcTemplate.queryForObject(
                        "INSERT INTO notifications(recipient_id, type, category, entity_type,"
                                + " entity_id, message, created_at) VALUES (?, 'post_removed',"
                                + " 'system', 'post', ?, ?, ?) RETURNING id",
                        UUID.class,
                        recipient,
                        UUID.randomUUID(),
                        "Policy violation",
                        minutesAgo(1));

        CursorPageResponse<NotificationResponse> page =
                notificationService.listNotifications(recipient, null, 10);

        assertThat(page.getContent()).hasSize(1);
        NotificationResponse response = page.getContent().get(0);
        assertThat(response.id()).isEqualTo(notificationId);
        assertThat(response.actor()).isNull();
        assertThat(response.type()).isEqualTo(NotificationType.POST_REMOVED);
        assertThat(response.message()).isEqualTo("Policy violation");
    }

    private OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
    }

    private UUID insertUser(String username, boolean deleted) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users(username, email, display_name, is_verified, deleted_at)"
                        + " VALUES (?, ?, ?, false, CASE WHEN ? THEN NOW() ELSE NULL END)"
                        + " RETURNING id",
                UUID.class,
                username,
                username + "@example.com",
                username,
                deleted);
    }

    private UUID insertNotification(UUID recipientId, UUID actorId, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO notifications(recipient_id, actor_id, type, category, created_at)"
                        + " VALUES (?, ?, 'follow', 'follow', ?) RETURNING id",
                UUID.class,
                recipientId,
                actorId,
                createdAt);
    }
}
