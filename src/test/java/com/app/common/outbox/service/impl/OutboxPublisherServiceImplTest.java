package com.app.common.outbox.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.annotation.Transactional;

import com.app.common.config.rabbit.RabbitMqTopologyConfig;
import com.app.common.outbox.config.OutboxPublisherProperties;
import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.enums.OutboxEventStatus;
import com.app.common.outbox.model.DomainEventEnvelope;
import com.app.common.outbox.observability.OutboxMetrics;
import com.app.common.outbox.service.OutboxPublisherStateService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceImplTest {

    // A real, minimal OTel-backed Tracer, built the same way Boot's auto-configuration would,
    // without a full application context. OutboxTraceRelay and the publisher both call real
    // OpenTelemetry API (Span.current(), context propagation) that a mocked Tracer cannot fake.
    private static AnnotationConfigApplicationContext tracingContext;
    private static Tracer tracer;
    private static InMemorySpanExporter spanExporter;

    @Mock private OutboxPublisherStateService outboxPublisherStateService;
    @Mock private RabbitTemplate rabbitTemplate;

    private OutboxPublisherProperties properties;
    private OutboxTraceRelay outboxTraceRelay;
    private SimpleMeterRegistry meterRegistry;
    private OutboxMetrics outboxMetrics;
    private OutboxPublisherServiceImpl service;

    @BeforeAll
    static void setUpTracing() {
        spanExporter = InMemorySpanExporter.create();
        tracingContext = new AnnotationConfigApplicationContext();
        tracingContext.registerBean(
                "otelSpanProcessorCustomizer",
                SdkTracerProviderBuilderCustomizer.class,
                () ->
                        (SdkTracerProviderBuilder builder) ->
                                builder.addSpanProcessor(SimpleSpanProcessor.create(spanExporter)));
        tracingContext.register(
                OpenTelemetrySdkAutoConfiguration.class,
                OpenTelemetryTracingAutoConfiguration.class);
        tracingContext.refresh();
        tracer = tracingContext.getBean(Tracer.class);
    }

    @AfterAll
    static void tearDownTracing() {
        tracingContext.close();
    }

    @BeforeEach
    void setUp() {
        spanExporter.reset();
        properties = new OutboxPublisherProperties();
        properties.setConfirmTimeout(Duration.ofMillis(20));
        outboxTraceRelay = new OutboxTraceRelay(tracer);
        meterRegistry = new SimpleMeterRegistry();
        outboxMetrics = new OutboxMetrics(meterRegistry);
        service =
                new OutboxPublisherServiceImpl(
                        outboxPublisherStateService,
                        rabbitTemplate,
                        properties,
                        ObservationRegistry.create(),
                        tracer,
                        outboxTraceRelay,
                        outboxMetrics);
    }

    @Test
    void publishDueEvents_confirmSuccessMarksPublished() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markPublished(eq(event), any(OffsetDateTime.class)))
                .thenReturn(true);
        completeConfirm(true, null);

        int attempted = service.publishDueEvents();

        assertThat(attempted).isOne();
        verify(rabbitTemplate)
                .send(
                        eq(RabbitMqTopologyConfig.SOCIAL_EVENTS_EXCHANGE),
                        eq(event.getRoutingKey()),
                        any(Message.class),
                        any(CorrelationData.class));
        verify(outboxPublisherStateService).markPublished(eq(event), any(OffsetDateTime.class));
        verify(outboxPublisherStateService, never())
                .markFailed(any(OutboxEvent.class), anyInt(), any(OffsetDateTime.class), any());
        verify(outboxPublisherStateService, never())
                .markDead(any(OutboxEvent.class), anyInt(), any(OffsetDateTime.class), any());
    }

    @Test
    void publishDueEvents_publishFailureSchedulesRetry() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markFailed(
                        eq(event), eq(1), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publishDueEvents();

        ArgumentCaptor<OffsetDateTime> nextRetryCaptor =
                ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(outboxPublisherStateService)
                .markFailed(eq(event), eq(1), nextRetryCaptor.capture(), any());
        assertThat(nextRetryCaptor.getValue()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        verify(outboxPublisherStateService, never()).markPublished(any(), any());
        verify(outboxPublisherStateService, never()).markDead(any(), anyInt(), any(), any());
    }

    @Test
    void publishDueEvents_publishFailure_logsErrorWithEventId() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markFailed(
                        eq(event), eq(1), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        Logger logger = (Logger) LoggerFactory.getLogger(OutboxPublisherServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.publishDueEvents();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anyMatch(
                        e ->
                                e.getLevel() == Level.ERROR
                                        && e.getFormattedMessage()
                                                .contains(event.getEventId().toString()));
    }

    @Test
    void publishDueEvents_maxAttemptsMarksDead() {
        OutboxEvent event = outboxEvent(2);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markDead(
                        eq(event), eq(3), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publishDueEvents();

        verify(outboxPublisherStateService)
                .markDead(eq(event), eq(3), any(OffsetDateTime.class), any());
        verify(outboxPublisherStateService, never()).markPublished(any(), any());
        verify(outboxPublisherStateService, never())
                .markFailed(any(OutboxEvent.class), anyInt(), any(OffsetDateTime.class), any());
    }

    @Test
    void publishDueEvents_nackDoesNotMarkPublished() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markFailed(
                        eq(event), eq(1), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        completeConfirm(false, "nack");

        service.publishDueEvents();

        verify(outboxPublisherStateService).markFailed(eq(event), eq(1), any(), any());
        verify(outboxPublisherStateService, never()).markPublished(any(), any());
    }

    @Test
    void publishDueEvents_markPublishedFailureDoesNotRollbackPreviousEventState() {
        OutboxEvent first = outboxEvent(0);
        OutboxEvent second = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(first, second));
        completeConfirm(true, null);
        doThrow(new IllegalStateException("database unavailable"))
                .when(outboxPublisherStateService)
                .markPublished(eq(second), any(OffsetDateTime.class));
        when(outboxPublisherStateService.markPublished(eq(first), any(OffsetDateTime.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.publishDueEvents())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(outboxPublisherStateService).markPublished(eq(first), any(OffsetDateTime.class));
        verify(outboxPublisherStateService).markPublished(eq(second), any(OffsetDateTime.class));
        verify(outboxPublisherStateService, never()).markFailed(any(), anyInt(), any(), any());
        verify(outboxPublisherStateService, never()).markDead(any(), anyInt(), any(), any());
    }

    @Test
    void publishDueEvents_timeoutDoesNotMarkPublishedBeforeConfirm() {
        OutboxEvent event = outboxEvent(0);
        properties.setConfirmTimeout(Duration.ofMillis(1));
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markFailed(
                        eq(event), eq(1), any(OffsetDateTime.class), any()))
                .thenReturn(true);

        service.publishDueEvents();

        verify(outboxPublisherStateService).markFailed(eq(event), eq(1), any(), any());
        verify(outboxPublisherStateService, never()).markPublished(any(), any());
    }

    @Test
    void publishDueEvents_executionExceptionOnConfirm_marksEventFailed() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markFailed(
                        eq(event), eq(1), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        doAnswer(
                        invocation -> {
                            CorrelationData cd = invocation.getArgument(3);
                            cd.getFuture()
                                    .completeExceptionally(
                                            new ExecutionException(
                                                    "broker error", new RuntimeException("root")));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publishDueEvents();

        verify(outboxPublisherStateService).markFailed(eq(event), eq(1), any(), any());
        verify(outboxPublisherStateService, never()).markPublished(any(), any());
    }

    @Test
    void publishDueEvents_interruptedOnConfirm_marksEventFailedAndRestoresInterrupt() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markFailed(
                        eq(event), eq(1), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        Thread.currentThread().interrupt();

        try {
            service.publishDueEvents();
        } finally {
            Thread.interrupted();
        }

        verify(outboxPublisherStateService).markFailed(eq(event), eq(1), any(), any());
        verify(outboxPublisherStateService, never()).markPublished(any(), any());
    }

    @Test
    void publishDueEvents_messageReturned_marksEventFailed() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markFailed(
                        eq(event), eq(1), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        doAnswer(
                        invocation -> {
                            CorrelationData cd = invocation.getArgument(3);
                            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                            cd.setReturned(
                                    new ReturnedMessage(
                                            MessageBuilder.withBody(new byte[0]).build(),
                                            312,
                                            "NO_ROUTE",
                                            "social.events",
                                            event.getRoutingKey()));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publishDueEvents();

        verify(outboxPublisherStateService).markFailed(eq(event), eq(1), any(), any());
        verify(outboxPublisherStateService, never()).markPublished(any(), any());
    }

    @Test
    void publishDueEvents_nullExceptionMessage_usesDefaultTruncatedMessage() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        doThrow(new AmqpException((String) null))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publishDueEvents();

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxPublisherStateService)
                .markFailed(eq(event), eq(1), any(OffsetDateTime.class), errorCaptor.capture());
        assertThat(errorCaptor.getValue()).isEqualTo("Unknown outbox publish failure");
    }

    @Test
    void publishDueEvents_markPublishedReturnsFalse_doesNotThrow() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        // Default Mockito stub returns false
        when(outboxPublisherStateService.markPublished(eq(event), any(OffsetDateTime.class)))
                .thenReturn(false);
        completeConfirm(true, null);

        int attempted = service.publishDueEvents();

        assertThat(attempted).isOne();
        verify(outboxPublisherStateService).markPublished(eq(event), any(OffsetDateTime.class));
    }

    @Test
    void publishDueEvents_emptyClaim_opensNoObservation() {
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of());

        int attempted = service.publishDueEvents();

        assertThat(attempted).isZero();
        assertThat(spanExporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void publishDueEvents_publishedOutcome_recordsPublishedTag() {
        OutboxEvent event = outboxEvent(0);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markPublished(eq(event), any(OffsetDateTime.class)))
                .thenReturn(true);
        completeConfirm(true, null);

        service.publishDueEvents();

        assertThat(
                        meterRegistry
                                .get("luvax.outbox.publish")
                                .tag("outcome", "published")
                                .timer()
                                .count())
                .isEqualTo(1);
    }

    @Test
    void publishDueEvents_deadOutcome_recordsDeadTag() {
        OutboxEvent event = outboxEvent(2);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markDead(
                        eq(event), eq(3), any(OffsetDateTime.class), any()))
                .thenReturn(true);
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publishDueEvents();

        assertThat(meterRegistry.get("luvax.outbox.publish").tag("outcome", "dead").timer().count())
                .isEqualTo(1);
    }

    @Test
    void publishDueEvents_storedContext_sendsWithOriginCurrent() {
        String originTraceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        OutboxEvent event = outboxEventWithTraceParent(originTraceparent);
        when(outboxPublisherStateService.claimPublishableBatch(any(OffsetDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxPublisherStateService.markPublished(eq(event), any(OffsetDateTime.class)))
                .thenReturn(true);
        AtomicReference<Span> currentDuringSend = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            currentDuringSend.set(Span.current());
                            CorrelationData cd = invocation.getArgument(3);
                            cd.getFuture().complete(new CorrelationData.Confirm(true, null));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        service.publishDueEvents();

        assertThat(currentDuringSend.get()).isNotNull();
        assertThat(currentDuringSend.get().getSpanContext().getTraceId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void publishDueEvents_isNotTransactional() throws NoSuchMethodException {
        Method method = OutboxPublisherServiceImpl.class.getMethod("publishDueEvents");

        assertThat(method.getAnnotation(Transactional.class)).isNull();
    }

    private void completeConfirm(boolean ack, String reason) {
        doAnswer(
                        invocation -> {
                            CorrelationData correlationData = invocation.getArgument(3);
                            correlationData
                                    .getFuture()
                                    .complete(new CorrelationData.Confirm(ack, reason));
                            return null;
                        })
                .when(rabbitTemplate)
                .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    private OutboxEvent outboxEventWithTraceParent(String traceParent) {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        DomainEventEnvelope payload =
                new DomainEventEnvelope(
                        eventId,
                        "user.registered.v1",
                        occurredAt,
                        aggregateId,
                        "user",
                        aggregateId,
                        Map.of("userId", aggregateId.toString()));
        return OutboxEvent.builder()
                .id(id)
                .eventId(eventId)
                .claimId(UUID.randomUUID())
                .aggregateType("user")
                .aggregateId(aggregateId)
                .eventType("user.registered.v1")
                .routingKey("user.registered.v1")
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .nextRetryAt(occurredAt)
                .createdAt(occurredAt)
                .traceParent(traceParent)
                .build();
    }

    private OutboxEvent outboxEvent(int attemptCount) {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        DomainEventEnvelope payload =
                new DomainEventEnvelope(
                        eventId,
                        "user.registered.v1",
                        occurredAt,
                        aggregateId,
                        "user",
                        aggregateId,
                        Map.of("userId", aggregateId.toString()));
        return OutboxEvent.builder()
                .id(id)
                .eventId(eventId)
                .claimId(UUID.randomUUID())
                .aggregateType("user")
                .aggregateId(aggregateId)
                .eventType("user.registered.v1")
                .routingKey("user.registered.v1")
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(attemptCount)
                .nextRetryAt(occurredAt)
                .createdAt(occurredAt)
                .build();
    }
}
