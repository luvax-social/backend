package com.app.common.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.analytics.ClickHouseOperations;
import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.testsupport.ClickHouseTestSupport;
import com.app.testsupport.TestContainerImages;
import com.rabbitmq.client.Channel;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * A hung ClickHouse pauses ingestion instead of dead-lettering it: the breaker opens, the listener
 * container stops, the messages wait ready in their queue, and after recovery every row arrives
 * exactly once.
 *
 * <p>The consumer here is a stand-in that follows the pattern every analytics consumer follows:
 * acknowledge on success, requeue on {@link ClickHouseUnavailableException}, dead-letter anything
 * else. It carries the id of the admin action replication listener, so the controller treats it as
 * that listener.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "management.health.elasticsearch.enabled=false",
            "app.outbox.publisher.enabled=false",
            // A paused server accepts the connection and never answers, so the socket timeout is
            // what turns the hang into a failure the breaker can count.
            "app.analytics.clickhouse.writer.socket-timeout=PT1S",
            "app.analytics.clickhouse.connection-timeout=PT1S"
        })
@Testcontainers
@Import(AnalyticsOutageIT.OutageConsumerConfig.class)
class AnalyticsOutageIT {

    static final String QUEUE = "test.analytics.outage.queue";
    static final String DEAD_LETTER_QUEUE = "test.analytics.outage.dlq";
    static final String DEAD_LETTER_ROUTING_KEY = "test.analytics.outage.dead-letter";
    static final String MALFORMED_PREFIX = "malformed:";
    static final OffsetDateTime ACTION_TIME = OffsetDateTime.parse("2026-09-01T00:00:00Z");

    static final ClickHouseContainer clickhouse = ClickHouseTestSupport.startProvisioned();

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(TestContainerImages.POSTGRES);

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> rabbit =
            new GenericContainer<>(DockerImageName.parse(TestContainerImages.RABBITMQ))
                    .withExposedPorts(5672);

    @DynamicPropertySource
    static void register(DynamicPropertyRegistry r) {
        ClickHouseTestSupport.register(r, clickhouse);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> "guest");
        r.add("spring.rabbitmq.password", () -> "guest");
        r.add("JWT_SECRET", () -> "analytics-outage-it-secret-32-characters!!");
        r.add("JWT_ISSUER", () -> "https://analytics-outage.it.local");
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

    @TestConfiguration
    static class OutageConsumerConfig {

        @Bean
        Queue outageQueue() {
            return QueueBuilder.durable(QUEUE)
                    .withArgument(
                            "x-dead-letter-exchange",
                            RabbitMqTopologyConfig.SOCIAL_EVENTS_DEAD_LETTER_EXCHANGE)
                    .withArgument("x-dead-letter-routing-key", DEAD_LETTER_ROUTING_KEY)
                    .build();
        }

        @Bean
        Queue outageDeadLetterQueue() {
            return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
        }

        @Bean
        Binding outageDeadLetterBinding(
                Queue outageDeadLetterQueue, TopicExchange socialEventsDeadLetterExchange) {
            return BindingBuilder.bind(outageDeadLetterQueue)
                    .to(socialEventsDeadLetterExchange)
                    .with(DEAD_LETTER_ROUTING_KEY);
        }

        @Bean
        BlockingQueue<UUID> processedIds() {
            return new LinkedBlockingQueue<>();
        }

        @Bean
        OutageConsumer outageConsumer(
                ClickHouseOperations operations, BlockingQueue<UUID> processedIds) {
            return new OutageConsumer(operations, processedIds);
        }
    }

    static class OutageConsumer {

        private final ClickHouseOperations operations;
        private final BlockingQueue<UUID> processedIds;

        OutageConsumer(ClickHouseOperations operations, BlockingQueue<UUID> processedIds) {
            this.operations = operations;
            this.processedIds = processedIds;
        }

        @RabbitListener(
                id = AnalyticsListenerIds.ADMIN_ACTION_REPLICATION,
                queues = QUEUE,
                autoStartup = "false")
        public void consume(Message message, Channel channel) throws Exception {
            long tag = message.getMessageProperties().getDeliveryTag();
            try {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                boolean malformed = body.startsWith(MALFORMED_PREFIX);
                UUID id = UUID.fromString(body.replaceFirst("^" + MALFORMED_PREFIX, ""));
                operations.write(
                        "test.outage.insert",
                        client ->
                                client.sql(
                                                "INSERT INTO admin_actions (id, action_type,"
                                                        + " created_at, row_version) SETTINGS"
                                                        + " async_insert = 1,"
                                                        + " wait_for_async_insert = 1 VALUES (:id,"
                                                        + " 'ban_user', :createdAt, 1)")
                                        .param("id", id)
                                        .param("createdAt", malformed ? "not-a-date" : ACTION_TIME)
                                        .update());
                channel.basicAck(tag, false);
                processedIds.add(id);
            } catch (ClickHouseUnavailableException e) {
                channel.basicNack(tag, false, true);
            } catch (RuntimeException e) {
                channel.basicNack(tag, false, false);
            }
        }
    }

    @Autowired private AnalyticsSchemaGate gate;
    @Autowired private AnalyticsIngestionController controller;
    @Autowired private CircuitBreakerRegistry breakers;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private BlockingQueue<UUID> processedIds;

    private UUID send() {
        UUID id = UUID.randomUUID();
        rabbitTemplate.send("", QUEUE, new Message(id.toString().getBytes(StandardCharsets.UTF_8)));
        return id;
    }

    private com.rabbitmq.client.AMQP.Queue.DeclareOk queueState(String name) {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(name));
    }

    private long countRows(List<UUID> ids) throws Exception {
        try (Connection admin = ClickHouseTestSupport.adminConnection(clickhouse);
                Statement statement = admin.createStatement();
                ResultSet rows =
                        statement.executeQuery(
                                "SELECT count() FROM luvax_analytics.admin_actions FINAL WHERE id"
                                        + " IN ("
                                        + String.join(
                                                ",",
                                                ids.stream().map(id -> "'" + id + "'").toList())
                                        + ")")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void outage_pausesIngestionKeepsMessagesReadyAndDrainsExactlyOnceAfterRecovery()
            throws Exception {
        assertThat(gate.attempt()).isTrue();
        controller.reconcile();
        assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
        CircuitBreaker breaker =
                breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME);
        breaker.reset();

        UUID healthy = send();
        assertThat(processedIds.poll(30, TimeUnit.SECONDS)).isEqualTo(healthy);

        CompletableFuture<Void> opened = new CompletableFuture<>();
        breaker.getEventPublisher()
                .onStateTransition(
                        event -> {
                            if (event.getStateTransition().getToState()
                                    == CircuitBreaker.State.OPEN) {
                                opened.complete(null);
                            }
                        });
        clickhouse.getDockerClient().pauseContainerCmd(clickhouse.getContainerId()).exec();
        List<UUID> sentDuringOutage = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                sentDuringOutage.add(send());
            }

            opened.get(90, TimeUnit.SECONDS);
            controller.awaitIdle(30, TimeUnit.SECONDS);

            assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION))
                    .isFalse();
            assertThat(queueState(QUEUE).getConsumerCount()).isZero();
            assertThat(queueState(QUEUE).getMessageCount()).isEqualTo(sentDuringOutage.size());
            assertThat(queueState(DEAD_LETTER_QUEUE).getMessageCount()).isZero();
        } finally {
            clickhouse.getDockerClient().unpauseContainerCmd(clickhouse.getContainerId()).exec();
        }

        // The breaker half-opens by itself after its open wait, which starts the container again.
        Set<UUID> drained = new HashSet<>();
        for (int i = 0; i < sentDuringOutage.size(); i++) {
            UUID id = processedIds.poll(120, TimeUnit.SECONDS);
            assertThat(id).as("message %d of the backlog", i).isNotNull();
            drained.add(id);
        }

        assertThat(drained).containsExactlyInAnyOrderElementsOf(sentDuringOutage);
        assertThat(queueState(QUEUE).getMessageCount()).isZero();
        assertThat(queueState(DEAD_LETTER_QUEUE).getMessageCount()).isZero();
        List<UUID> everything = new ArrayList<>(sentDuringOutage);
        everything.add(healthy);
        assertThat(countRows(everything)).isEqualTo(everything.size());
        assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
    }

    @Test
    void rejectedRequest_deadLettersAtOnceAndNeverPausesIngestion() throws Exception {
        assertThat(gate.attempt()).isTrue();
        controller.reconcile();
        CircuitBreaker breaker =
                breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME);
        breaker.reset();

        // ClickHouse cannot parse the timestamp, which is the request being wrong, not the server.
        rabbitTemplate.send(
                "",
                QUEUE,
                new Message(
                        (MALFORMED_PREFIX + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));

        Message deadLettered = rabbitTemplate.receive(DEAD_LETTER_QUEUE, 30_000);

        assertThat(deadLettered).isNotNull();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
    }
}
