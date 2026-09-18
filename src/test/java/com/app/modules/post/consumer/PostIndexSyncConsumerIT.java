package com.app.modules.post.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.model.DomainEventEnvelopeJson;
import com.app.modules.mail.service.MailService;
import com.app.modules.post.messaging.PostEventTypes;
import com.app.modules.post.search.PostDocument;
import com.app.modules.post.search.PostSearchRepository;
import com.app.modules.recommendation.client.GorseClient;
import com.rabbitmq.client.Channel;

@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.post.consumer.enabled=true",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class PostIndexSyncConsumerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
                    .withExposedPorts(5672);

    @Container
    static ElasticsearchContainer elasticsearch =
            new ElasticsearchContainer(
                            DockerImageName.parse(
                                    "docker.elastic.co/elasticsearch/elasticsearch:9.0.3"))
                    .withEnv("xpack.security.enabled", "false");

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("app.elasticsearch.uris", () -> "http://" + elasticsearch.getHttpHostAddress());
        r.add("JWT_SECRET", () -> "post-sync-consumer-it-secret-32-chars-min!!!!");
        r.add("JWT_ISSUER", () -> "https://post-sync-consumer-it.test.local");
        r.add("JWT_AUDIENCE", () -> "post-sync-consumer-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Post Sync Consumer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        // Seed runner stays off so the index begins empty and each test owns its documents.
        r.add("app.post.seed.enabled", () -> false);
    }

    // Gorse is an external HTTP service with no container in this test, and the post index-sync
    // path calls it before writing to Elasticsearch. Left real, every upsert fails on connection
    // refused, the message is dead-lettered, and nothing is ever indexed.
    @MockitoBean private GorseClient gorseClient;

    @MockitoBean private MailService mailService;

    @Autowired private PostIndexSyncConsumer consumer;
    @Autowired private PostSearchRepository postSearchRepository;
    @Autowired private ElasticsearchOperations elasticsearchOperations;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE processed_messages");
        jdbcTemplate.execute("DELETE FROM posts");
        jdbcTemplate.execute("DELETE FROM users");
        IndexOperations ops = elasticsearchOperations.indexOps(PostDocument.class);
        if (ops.exists()) {
            ops.delete();
        }
    }

    @Test
    void upsert_publishedPost_indexesDocument() throws Exception {
        ensureIndexExists();
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        insertUser(userId);
        insertPost(postId, userId, "published", false, "sunset over the bay");

        DomainEventEnvelope env = upsertEnvelope(UUID.randomUUID(), postId, userId);
        Channel channel = mock(Channel.class);

        consumer.consume(buildMessage(env), channel);

        verify(channel).basicAck(0L, false);
        elasticsearchOperations.indexOps(PostDocument.class).refresh();
        Optional<PostDocument> stored = postSearchRepository.findById(postId.toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().getCaption()).isEqualTo("sunset over the bay");
        assertThat(stored.get().getStatus()).isEqualTo("published");
        assertThat(stored.get().getUserId()).isEqualTo(userId.toString());
    }

    @Test
    void delete_softDeletedPost_removesDocument() throws Exception {
        ensureIndexExists();
        UUID postId = UUID.randomUUID();
        postSearchRepository.save(
                PostDocument.builder()
                        .id(postId.toString())
                        .userId(UUID.randomUUID().toString())
                        .caption("doomed")
                        .status("published")
                        .hashtagIds(List.of())
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build());
        elasticsearchOperations.indexOps(PostDocument.class).refresh();

        DomainEventEnvelope env = deleteEnvelope(UUID.randomUUID(), postId);
        Channel channel = mock(Channel.class);

        consumer.consume(buildMessage(env), channel);

        verify(channel).basicAck(0L, false);
        elasticsearchOperations.indexOps(PostDocument.class).refresh();
        assertThat(postSearchRepository.findById(postId.toString())).isEmpty();
    }

    @Test
    void idempotent_duplicateUpsert_processedOnce() throws Exception {
        ensureIndexExists();
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        insertUser(userId);
        insertPost(postId, userId, "published", false, "first");

        DomainEventEnvelope first = upsertEnvelope(eventId, postId, userId);
        DomainEventEnvelope second = upsertEnvelope(eventId, postId, userId);
        Channel channel = mock(Channel.class);

        consumer.consume(buildMessage(first), channel);
        consumer.consume(buildMessage(second), channel);

        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM processed_messages"
                                + " WHERE consumer_name = ? AND event_id = ?",
                        Integer.class,
                        "post-index-sync-consumer",
                        eventId);
        assertThat(rows).isEqualTo(1);

        elasticsearchOperations.indexOps(PostDocument.class).refresh();
        Optional<PostDocument> stored = postSearchRepository.findById(postId.toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().getCaption()).isEqualTo("first");
    }

    @Test
    void outOfOrder_deleteBeforeUpsert_documentAbsent() throws Exception {
        ensureIndexExists();
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        insertUser(userId);
        insertPost(postId, userId, "removed", true, "resurrected");

        postSearchRepository.save(
                PostDocument.builder()
                        .id(postId.toString())
                        .userId(userId.toString())
                        .caption("stale")
                        .status("published")
                        .hashtagIds(List.of())
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build());
        elasticsearchOperations.indexOps(PostDocument.class).refresh();

        Channel channel = mock(Channel.class);
        consumer.consume(buildMessage(deleteEnvelope(UUID.randomUUID(), postId)), channel);
        consumer.consume(buildMessage(upsertEnvelope(UUID.randomUUID(), postId, userId)), channel);

        elasticsearchOperations.indexOps(PostDocument.class).refresh();
        // Q4 gate: the source row is soft-deleted/non-published, so the stale upsert is skipped and
        // the delete is not undone.
        assertThat(postSearchRepository.findById(postId.toString())).isEmpty();
    }

    @Test
    void dlq_malformedMessage_routes() {
        drainQueue(RabbitMqTopologyConfig.POST_INDEX_SYNC_QUEUE);
        drainQueue(RabbitMqTopologyConfig.POST_INDEX_SYNC_DEAD_LETTER_QUEUE);

        rabbitTemplate.convertAndSend(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                PostEventTypes.POST_INDEX_UPSERT_V1,
                "not-json".getBytes(StandardCharsets.UTF_8));

        Message dead =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.POST_INDEX_SYNC_DEAD_LETTER_QUEUE, 5000L);
        assertThat(dead).isNotNull();
    }

    private void insertUser(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email) VALUES (?, ?, ?)",
                userId,
                "user_" + userId.toString().substring(0, 8),
                userId + "@test.local");
    }

    private void insertPost(
            UUID postId, UUID userId, String status, boolean softDeleted, String caption) {
        jdbcTemplate.update(
                "INSERT INTO posts (id, user_id, post_type, status, caption, deleted_at)"
                        + " VALUES (?, ?, 'image', ?, ?, ?)",
                postId,
                userId,
                status,
                caption,
                softDeleted ? OffsetDateTime.now(ZoneOffset.UTC) : null);
    }

    private void drainQueue(String queue) {
        while (rabbitTemplate.receive(queue) != null) {
            // Discard any residual messages so the assertion observes only this test's delivery.
        }
    }

    private void ensureIndexExists() {
        IndexOperations ops = elasticsearchOperations.indexOps(PostDocument.class);
        if (!ops.exists()) {
            ops.createWithMapping();
        }
    }

    private static Message buildMessage(DomainEventEnvelope env) {
        return MessageBuilder.withBody(
                        DomainEventEnvelopeJson.write(env).getBytes(StandardCharsets.UTF_8))
                .setDeliveryTag(0L)
                .build();
    }

    private static DomainEventEnvelope upsertEnvelope(UUID eventId, UUID postId, UUID userId) {
        Map<String, Object> data =
                Map.of(
                        "postId",
                        postId.toString(),
                        "userId",
                        userId.toString(),
                        "status",
                        "published",
                        "hashtagIds",
                        List.of(),
                        "version",
                        1,
                        "createdAt",
                        OffsetDateTime.now(ZoneOffset.UTC).toString());
        return new DomainEventEnvelope(
                eventId,
                PostEventTypes.POST_INDEX_UPSERT_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "post",
                postId,
                data);
    }

    private static DomainEventEnvelope deleteEnvelope(UUID eventId, UUID postId) {
        Map<String, Object> data = Map.of("postId", postId.toString(), "version", 1);
        return new DomainEventEnvelope(
                eventId,
                PostEventTypes.POST_INDEX_DELETE_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "post",
                postId,
                data);
    }
}
