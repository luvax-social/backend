package com.app.modules.post.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.app.modules.post.messaging.PostEventTypes;
import com.rabbitmq.client.Channel;

/**
 * Drives {@link PostNotificationConsumer} against a real database: a like opens or joins the post
 * owner's group, an unlike retracts only that actor, a redelivery is absorbed by the inbox, and an
 * event whose like no longer exists writes nothing.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.post.notification-consumer.enabled=true",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class PostNotificationConsumerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
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
        r.add("JWT_SECRET", () -> "post-consumer-it-secret-32-characters-minimum!!");
        r.add("JWT_ISSUER", () -> "https://post-consumer-it.test.local");
        r.add("JWT_AUDIENCE", () -> "post-consumer-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Post Consumer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @Autowired private PostNotificationConsumer consumer;
    @Autowired private JdbcTemplate jdbc;

    // Declared at the concrete type, as in CommentNotificationConsumerIT: the moderation mail
    // handler injects AbstractTemplateMailSender, so an interface-typed override would not fit.
    @MockitoBean private AbstractTemplateMailSender mailSender;

    private UUID owner;
    private UUID post;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM notifications");
        owner = user("owner");
        post =
                jdbc.queryForObject(
                        "INSERT INTO posts (user_id) VALUES (?) RETURNING id", UUID.class, owner);
    }

    @Test
    void consume_twoLikes_landInOneGroupLedByTheNewestLiker() throws Exception {
        UUID first = user("first");
        UUID second = user("second");

        like(first, UUID.randomUUID());
        like(second, UUID.randomUUID());

        List<Map<String, Object>> rows = liveRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("type", "like_post")
                .containsEntry("recipient_id", owner)
                .containsEntry("actor_id", second)
                .containsEntry("entity_id", post)
                .containsEntry("post_id", post)
                .containsEntry("actor_count", 2);
    }

    @Test
    void consume_redeliveredLike_isAbsorbedByTheInbox() throws Exception {
        UUID liker = user("liker");
        UUID eventId = UUID.randomUUID();

        like(liker, eventId);
        deliver(eventId, PostEventTypes.POST_LIKED_V1, liker);

        assertThat(liveRows())
                .singleElement()
                .extracting(row -> row.get("actor_count"))
                .isEqualTo(1);
    }

    @Test
    void consume_likeAlreadyWithdrawn_writesNothing() throws Exception {
        UUID liker = user("liker");

        deliver(UUID.randomUUID(), PostEventTypes.POST_LIKED_V1, liker);

        assertThat(liveRows()).isEmpty();
    }

    @Test
    void consume_unlike_retractsOnlyThatActorAndKeepsThePosition() throws Exception {
        UUID first = user("first");
        UUID second = user("second");
        like(first, UUID.randomUUID());
        like(second, UUID.randomUUID());
        Object activityBefore = liveRows().get(0).get("activity_at");

        unlike(second);

        Map<String, Object> row = liveRows().get(0);
        assertThat(row.get("actor_count")).isEqualTo(1);
        assertThat(row.get("actor_id")).isEqualTo(first);
        assertThat(row.get("activity_at")).isEqualTo(activityBefore);
    }

    @Test
    void consume_unlikeOfTheLastActor_removesTheRow() throws Exception {
        UUID liker = user("liker");
        like(liker, UUID.randomUUID());

        unlike(liker);

        assertThat(liveRows()).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM notifications WHERE deleted_at IS NOT NULL"
                                        + " AND NOT is_group_open",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void consume_unlikeFollowedByARelike_retractsNothing() throws Exception {
        UUID liker = user("liker");
        like(liker, UUID.randomUUID());

        // The unlike event arrives after the account has liked the post again.
        deliver(UUID.randomUUID(), PostEventTypes.POST_UNLIKED_V1, liker);

        assertThat(liveRows())
                .singleElement()
                .extracting(row -> row.get("actor_count"))
                .isEqualTo(1);
    }

    @Test
    void consume_missingPostId_isRejectedWithoutRequeue() throws Exception {
        Channel channel = mock(Channel.class);
        Map<String, Object> data = new HashMap<>();
        data.put("postOwnerId", owner.toString());
        data.put("userId", UUID.randomUUID().toString());

        consumer.consume(message(UUID.randomUUID(), PostEventTypes.POST_LIKED_V1, data), channel);

        verify(channel).basicNack(7L, false, false);
        assertThat(liveRows()).isEmpty();
    }

    private void like(UUID liker, UUID eventId) throws Exception {
        jdbc.update("INSERT INTO post_likes (user_id, post_id) VALUES (?, ?)", liker, post);
        deliver(eventId, PostEventTypes.POST_LIKED_V1, liker);
    }

    private void unlike(UUID liker) throws Exception {
        jdbc.update("DELETE FROM post_likes WHERE user_id = ? AND post_id = ?", liker, post);
        deliver(UUID.randomUUID(), PostEventTypes.POST_UNLIKED_V1, liker);
    }

    private void deliver(UUID eventId, String type, UUID liker) throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.toString());
        data.put("postOwnerId", owner.toString());
        data.put("userId", liker.toString());
        Channel channel = mock(Channel.class);
        consumer.consume(message(eventId, type, data), channel);
        verify(channel).basicAck(7L, false);
    }

    private List<Map<String, Object>> liveRows() {
        return jdbc.queryForList(
                "SELECT type::text AS type, recipient_id, actor_id, entity_id, post_id,"
                        + " actor_count, activity_at FROM notifications WHERE deleted_at IS NULL");
    }

    private UUID user(String prefix) {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject(
                "INSERT INTO users (username, email, display_name) VALUES (?, ?, ?) RETURNING id",
                UUID.class,
                username,
                username + "@test.local",
                username);
    }

    private static Message message(UUID eventId, String type, Map<String, Object> data) {
        DomainEventEnvelope envelope =
                new DomainEventEnvelope(
                        eventId,
                        type,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        data.get("userId") == null
                                ? null
                                : UUID.fromString(data.get("userId").toString()),
                        "post",
                        data.get("postId") == null
                                ? UUID.randomUUID()
                                : UUID.fromString(data.get("postId").toString()),
                        data);
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(envelope).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(7L)
                .build();
    }
}
