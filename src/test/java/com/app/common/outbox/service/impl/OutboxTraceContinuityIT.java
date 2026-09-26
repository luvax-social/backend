package com.app.common.outbox.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.repository.OutboxEventRepository;
import com.app.common.outbox.service.OutboxPublisherService;
import com.app.common.outbox.service.OutboxService;
import com.app.modules.mail.service.MailService;
import com.app.testsupport.TestContainerImages;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

/**
 * Proves the shape from section 4.2 of the observability plan end to end: the HTTP-equivalent
 * origin span is the ancestor of the send and receive spans through the stored traceparent, while
 * the publisher's own relay span lives in a separate batch trace and only links to the origin.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "spring.docker.compose.enabled=false",
            "app.outbox.publisher.initial-delay=PT1H",
            "app.outbox.publisher.fixed-delay=PT1H",
            "app.outbox.publisher.confirm-timeout=PT5S",
            "spring.rabbitmq.publisher-confirm-type=correlated",
            "spring.rabbitmq.publisher-returns=true",
            "spring.rabbitmq.template.mandatory=true",
            "spring.rabbitmq.template.observation-enabled=true",
            "spring.rabbitmq.listener.simple.observation-enabled=true",
            "management.tracing.sampling.probability=1.0",
            "app.mail.consumer.enabled=false"
        })
@Testcontainers
class OutboxTraceContinuityIT {

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
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
        registry.add("JWT_SECRET", () -> "outbox-trace-it-secret-32-chars-min!!");
        registry.add("JWT_ISSUER", () -> "https://outbox-trace-it.test.local");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App Outbox Trace IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @TestConfiguration
    static class TracingTestConfig {

        static final CompletableFuture<Message> RECEIVED = new CompletableFuture<>();

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }

        @Bean
        SdkTracerProviderBuilderCustomizer inMemorySpanExporterCustomizer(
                InMemorySpanExporter exporter) {
            return builder -> builder.addSpanProcessor(SimpleSpanProcessor.create(exporter));
        }

        @Bean
        Queue testTraceQueue() {
            // Exclusive, not auto-delete: RabbitMQ 4.3 deprecates and rejects a transient,
            // non-exclusive queue outright (feature `transient_nonexcl_queues`), so a plain
            // auto-delete queue fails to declare at all under the image this plan pins.
            return QueueBuilder.nonDurable("test.trace.queue").exclusive().build();
        }

        @Bean
        Binding testTraceBinding(Queue testTraceQueue, TopicExchange socialEventsExchange) {
            return BindingBuilder.bind(testTraceQueue)
                    .to(socialEventsExchange)
                    .with("test.trace.#");
        }

        @RabbitListener(queues = "test.trace.queue")
        void onTestTraceMessage(Message message) {
            RECEIVED.complete(message);
        }
    }

    @Autowired private OutboxService outboxService;
    @Autowired private OutboxPublisherService outboxPublisherService;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private Tracer tracer;
    @Autowired private InMemorySpanExporter spanExporter;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private MailService mailService;

    @AfterEach
    void cleanup() {
        spanExporter.reset();
    }

    @Test
    void publishDueEvents_storedOrigin_consumerDescendsFromOriginAndRelayLinksToIt()
            throws Exception {
        UUID aggregateId = UUID.randomUUID();
        Span originSpan = tracer.nextSpan().name("test origin").start();
        OutboxEvent event;
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        try (Tracer.SpanInScope ignored = tracer.withSpan(originSpan)) {
            event =
                    transactionTemplate.execute(
                            status ->
                                    outboxService.enqueue(
                                            "test.trace.created.v1",
                                            "test.trace.created.v1",
                                            "test",
                                            aggregateId,
                                            null,
                                            Map.of("marker", "outbox-trace-continuity-it")));
        } finally {
            originSpan.end();
        }
        String originTraceId = originSpan.context().traceId();
        String originSpanId = originSpan.context().spanId();
        assertThat(event.getTraceParent()).isNotNull();

        outboxPublisherService.publishDueEvents();
        Message received = TracingTestConfig.RECEIVED.get(10, TimeUnit.SECONDS);

        String traceparentHeader =
                (String) received.getMessageProperties().getHeaders().get("traceparent");
        assertThat(traceparentHeader).isNotNull();
        assertThat(traceparentHeader.split("-")[1]).isEqualTo(originTraceId);

        List<SpanData> spans = awaitSpans(originTraceId);

        SpanData consumerSpan =
                spans.stream()
                        .filter(s -> s.getKind() == io.opentelemetry.api.trace.SpanKind.CONSUMER)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no consumer span exported"));
        assertThat(consumerSpan.getTraceId()).isEqualTo(originTraceId);

        SpanData producerSpan =
                spans.stream()
                        .filter(s -> s.getSpanId().equals(consumerSpan.getParentSpanId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("consumer span's parent not found"));
        assertThat(producerSpan.getKind()).isEqualTo(io.opentelemetry.api.trace.SpanKind.PRODUCER);
        assertThat(producerSpan.getParentSpanId()).isEqualTo(originSpanId);

        SpanData relaySpan =
                outboxRelaySpan(event.getEventType())
                        .orElseThrow(() -> new AssertionError("no relay span exported"));
        assertThat(relaySpan.getTraceId()).isNotEqualTo(originTraceId);
        assertThat(relaySpan.getLinks())
                .anyMatch(link -> link.getSpanContext().getTraceId().equals(originTraceId));
    }

    private List<SpanData> awaitSpans(String originTraceId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            boolean hasConsumer =
                    spans.stream()
                            .anyMatch(
                                    s ->
                                            s.getKind()
                                                            == io.opentelemetry.api.trace.SpanKind
                                                                    .CONSUMER
                                                    && s.getTraceId().equals(originTraceId));
            if (hasConsumer) {
                return spans;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for the consumer span to export");
    }

    private java.util.Optional<SpanData> outboxRelaySpan(String eventType) {
        return spanExporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("outbox relay " + eventType))
                .findFirst();
    }
}
