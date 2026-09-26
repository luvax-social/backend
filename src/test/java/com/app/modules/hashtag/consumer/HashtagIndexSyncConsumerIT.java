package com.app.modules.hashtag.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.app.modules.hashtag.messaging.HashtagEventTypes;
import com.app.modules.hashtag.search.HashtagDocument;
import com.app.modules.hashtag.search.HashtagSearchRepository;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;
import com.rabbitmq.client.Channel;

@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.enabled=false",
            "app.hashtag.consumer.enabled=true",
            "app.messaging.consumer.max-attempts=1",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true"
        })
@Testcontainers
class HashtagIndexSyncConsumerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @Container
    static ElasticsearchContainer elasticsearch =
            new ElasticsearchContainer(DockerImageName.parse(TestContainerImages.ELASTICSEARCH))
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
        r.add("JWT_SECRET", () -> "hashtag-sync-consumer-it-secret-32-chars-min!!");
        r.add("JWT_ISSUER", () -> "https://hashtag-sync-consumer-it.test.local");
        r.add("JWT_AUDIENCE", () -> "hashtag-sync-consumer-it");
        r.add("ACCESS_TOKEN_TTL", () -> 900L);
        r.add("REFRESH_TOKEN_TTL", () -> 3600L);
        r.add("APP_BASE_URL", () -> "http://localhost:8080");
        r.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        r.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        r.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        r.add("MAIL_FROM_NAME", () -> "App Hashtag Sync Consumer IT");
        r.add("MAIL_APP_NAME", () -> "App");
        r.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        r.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        r.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        r.add("spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
        // Seed runner stays off so the index begins empty and each test owns its documents.
        r.add("app.hashtag.seed.enabled", () -> false);
    }

    @MockitoBean private MailService mailService;

    @Autowired private HashtagIndexSyncConsumer consumer;
    @Autowired private HashtagSearchRepository hashtagSearchRepository;
    @Autowired private ElasticsearchOperations elasticsearchOperations;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE processed_messages");
        jdbcTemplate.execute("TRUNCATE hashtags CASCADE");
        IndexOperations ops = elasticsearchOperations.indexOps(HashtagDocument.class);
        if (ops.exists()) {
            ops.delete();
        }
    }

    @Test
    void upsert_validEvent_indexesDocument() throws Exception {
        ensureIndexExists();
        UUID hashtagId = UUID.randomUUID();
        insertHashtag(hashtagId, "java", 5);
        DomainEventEnvelope env = upsertEnvelope(UUID.randomUUID(), hashtagId);
        Channel channel = mock(Channel.class);

        consumer.consume(buildMessage(env), channel);

        verify(channel).basicAck(0L, false);
        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();
        Optional<HashtagDocument> stored = hashtagSearchRepository.findById(hashtagId.toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().getName()).isEqualTo("java");
        assertThat(stored.get().getPostCount()).isEqualTo(5);
    }

    @Test
    void delete_validEvent_removesDocument() throws Exception {
        ensureIndexExists();
        UUID hashtagId = UUID.randomUUID();
        hashtagSearchRepository.save(
                HashtagDocument.builder()
                        .id(hashtagId.toString())
                        .name("java")
                        .postCount(7)
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build());
        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();

        DomainEventEnvelope env = deleteEnvelope(UUID.randomUUID(), hashtagId);
        Channel channel = mock(Channel.class);

        consumer.consume(buildMessage(env), channel);

        verify(channel).basicAck(0L, false);
        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();
        assertThat(hashtagSearchRepository.findById(hashtagId.toString())).isEmpty();
    }

    @Test
    void idempotent_duplicateEvent_processedOnce() throws Exception {
        ensureIndexExists();
        UUID eventId = UUID.randomUUID();
        UUID hashtagId = UUID.randomUUID();
        insertHashtag(hashtagId, "java", 5);

        DomainEventEnvelope first = upsertEnvelope(eventId, hashtagId);
        DomainEventEnvelope second = upsertEnvelope(eventId, hashtagId);
        Channel channel = mock(Channel.class);

        consumer.consume(buildMessage(first), channel);
        consumer.consume(buildMessage(second), channel);

        Integer rows =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM processed_messages"
                                + " WHERE consumer_name = ? AND event_id = ?",
                        Integer.class,
                        "hashtag-index-sync-consumer",
                        eventId);
        assertThat(rows).isEqualTo(1);

        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();
        Optional<HashtagDocument> stored = hashtagSearchRepository.findById(hashtagId.toString());
        assertThat(stored).isPresent();
        assertThat(stored.get().getPostCount()).isEqualTo(5);
    }

    @Test
    void outOfOrder_upsertForZeroPostHashtag_documentAbsent() throws Exception {
        ensureIndexExists();
        UUID hashtagId = UUID.randomUUID();
        hashtagSearchRepository.save(
                HashtagDocument.builder()
                        .id(hashtagId.toString())
                        .name("java")
                        .postCount(3)
                        .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                        .build());
        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();

        // No hashtags row exists: a concurrent delete already removed the last association, so the
        // post_count gate must drop the stale doc rather than resurrect it from this late upsert.
        DomainEventEnvelope env = upsertEnvelope(UUID.randomUUID(), hashtagId);
        Channel channel = mock(Channel.class);

        consumer.consume(buildMessage(env), channel);

        verify(channel).basicAck(0L, false);
        elasticsearchOperations.indexOps(HashtagDocument.class).refresh();
        assertThat(hashtagSearchRepository.findById(hashtagId.toString())).isEmpty();
    }

    @Test
    void dlq_consumerThrowsOnEveryAttempt_messageRoutes() {
        drainQueue(RabbitMqTopologyConfig.HASHTAG_INDEX_SYNC_QUEUE);
        drainQueue(RabbitMqTopologyConfig.HASHTAG_INDEX_SYNC_DEAD_LETTER_QUEUE);

        rabbitTemplate.convertAndSend(
                RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE,
                HashtagEventTypes.HASHTAG_INDEX_UPSERT_V1,
                "not-json".getBytes(StandardCharsets.UTF_8));

        Message dead =
                rabbitTemplate.receive(
                        RabbitMqTopologyConfig.HASHTAG_INDEX_SYNC_DEAD_LETTER_QUEUE, 5000L);
        assertThat(dead).isNotNull();
    }

    private void drainQueue(String queue) {
        while (rabbitTemplate.receive(queue) != null) {
            // Discard any residual messages so the assertion observes only this test's delivery.
        }
    }

    private void ensureIndexExists() {
        IndexOperations ops = elasticsearchOperations.indexOps(HashtagDocument.class);
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

    private void insertHashtag(UUID hashtagId, String name, int postCount) {
        jdbcTemplate.update(
                "INSERT INTO hashtags (id, name, post_count, created_at)"
                        + " VALUES (?, ?, ?, now())",
                hashtagId,
                name,
                postCount);
    }

    private static DomainEventEnvelope upsertEnvelope(UUID eventId, UUID hashtagId) {
        Map<String, Object> data = Map.of("hashtagId", hashtagId.toString(), "version", 1);
        return new DomainEventEnvelope(
                eventId,
                HashtagEventTypes.HASHTAG_INDEX_UPSERT_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "hashtag",
                hashtagId,
                data);
    }

    private static DomainEventEnvelope deleteEnvelope(UUID eventId, UUID hashtagId) {
        Map<String, Object> data = Map.of("hashtagId", hashtagId.toString(), "version", 1);
        return new DomainEventEnvelope(
                eventId,
                HashtagEventTypes.HASHTAG_INDEX_DELETE_V1,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                "hashtag",
                hashtagId,
                data);
    }
}
