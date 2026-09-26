package com.app.common.outbox.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.opentelemetry.autoconfigure.logging.SdkLoggerProviderBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;

/**
 * Same context shape as {@link OutboxTraceContinuityIT}: proves the OTel Logback appender (D4)
 * attaches the current span's ids to every log record, independent of the outbox path itself.
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
class OutboxLogCorrelationIT {

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
        registry.add("JWT_SECRET", () -> "outbox-log-correlation-it-secret-32-min!!");
        registry.add("JWT_ISSUER", () -> "https://outbox-log-it.test.local");
        registry.add("ACCESS_TOKEN_TTL", () -> 900L);
        registry.add("REFRESH_TOKEN_TTL", () -> 3600L);
        registry.add("APP_BASE_URL", () -> "http://localhost:8080");
        registry.add("CORS_ALLOWED_ORIGINS", () -> "http://localhost:3000");
        registry.add("RESEND_API_KEY", () -> "re_test_dummy_key");
        registry.add("MAIL_FROM_ADDRESS", () -> "noreply@test.local");
        registry.add("MAIL_FROM_NAME", () -> "App Outbox Log IT");
        registry.add("MAIL_APP_NAME", () -> "App");
        registry.add("FRONTEND_BASE_URL", () -> "http://localhost:3000");
        registry.add("GOOGLE_CLIENT_ID", () -> "test-client-id");
        registry.add("GOOGLE_CLIENT_SECRET", () -> "test-client-secret");
        registry.add(
                "spring.datasource.hikari.data-source-properties.stringtype", () -> "unspecified");
    }

    @TestConfiguration
    static class LoggingTestConfig {

        @Bean
        InMemoryLogRecordExporter inMemoryLogRecordExporter() {
            return InMemoryLogRecordExporter.create();
        }

        @Bean
        SdkLoggerProviderBuilderCustomizer inMemoryLogRecordExporterCustomizer(
                InMemoryLogRecordExporter exporter) {
            return builder ->
                    builder.addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OutboxLogCorrelationIT.class);

    @Autowired private Tracer tracer;
    @Autowired private InMemoryLogRecordExporter logExporter;

    @MockitoBean private MailService mailService;

    @AfterEach
    void cleanup() {
        logExporter.reset();
    }

    @Test
    void logLine_writtenInsideSpan_exportedWithThatSpansTraceAndSpanId() {
        Span span = tracer.nextSpan().name("log-correlation-it span").start();
        String marker = "outbox-log-correlation-it-marker-" + System.nanoTime();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            log.info(marker);
        } finally {
            span.end();
        }

        List<LogRecordData> records = logExporter.getFinishedLogRecordItems();
        LogRecordData record =
                records.stream()
                        .filter(
                                r ->
                                        r.getBodyValue() != null
                                                && r.getBodyValue().asString().contains(marker))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("log line was never exported"));

        assertThat(record.getSpanContext().getTraceId()).isEqualTo(span.context().traceId());
        assertThat(record.getSpanContext().getSpanId()).isEqualTo(span.context().spanId());
    }
}
