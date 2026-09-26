package com.app.common.outbox.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.app.common.outbox.entity.OutboxEvent;
import com.app.common.outbox.enums.OutboxEventStatus;
import com.app.common.outbox.model.DomainEventEnvelope;

import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

class OutboxTraceRelayTest {

    private static final String TRACEPARENT =
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
    private static final String TRACESTATE = "congo=t61";

    private static AnnotationConfigApplicationContext context;
    private static Tracer tracer;

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.register(
                OpenTelemetrySdkAutoConfiguration.class,
                OpenTelemetryTracingAutoConfiguration.class);
        context.refresh();
        tracer = context.getBean(Tracer.class);
    }

    @AfterAll
    static void tearDown() {
        context.close();
    }

    @Test
    void makeOriginCurrent_storedContext_spanCurrentCarriesStoredIds() {
        OutboxTraceRelay relay = new OutboxTraceRelay(tracer);
        OutboxEvent event = eventWithTrace(TRACEPARENT, TRACESTATE);

        try (Scope ignored = relay.makeOriginCurrent(event)) {
            Span current = Span.current();
            assertThat(current.getSpanContext().getTraceId())
                    .isEqualTo("0af7651916cd43dd8448eb211c80319c");
            assertThat(current.getSpanContext().getSpanId()).isEqualTo("b7ad6b7169203331");
            assertThat(current.getSpanContext().getTraceState().get("congo")).isEqualTo("t61");
        }
    }

    @Test
    void makeOriginCurrent_noStoredContext_returnsNoopScope() {
        OutboxTraceRelay relay = new OutboxTraceRelay(tracer);
        OutboxEvent event = eventWithTrace(null, null);

        Scope scope = relay.makeOriginCurrent(event);

        assertThat(scope).isEqualTo(Scope.noop());
    }

    @Test
    void originLink_storedContext_idsMatch() {
        OutboxTraceRelay relay = new OutboxTraceRelay(tracer);
        OutboxEvent event = eventWithTrace(TRACEPARENT, TRACESTATE);

        var link = relay.originLink(event);

        assertThat(link).isPresent();
        assertThat(link.get().getTraceContext().traceId())
                .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(link.get().getTraceContext().spanId()).isEqualTo("b7ad6b7169203331");
    }

    @Test
    void originLink_noStoredContext_isEmpty() {
        OutboxTraceRelay relay = new OutboxTraceRelay(tracer);
        OutboxEvent event = eventWithTrace(null, null);

        assertThat(relay.originLink(event)).isEmpty();
    }

    private OutboxEvent eventWithTrace(String traceParent, String traceState) {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        DomainEventEnvelope payload =
                new DomainEventEnvelope(
                        eventId,
                        "user.registered.v1",
                        now,
                        aggregateId,
                        "user",
                        aggregateId,
                        Map.of());
        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .aggregateType("user")
                .aggregateId(aggregateId)
                .eventType("user.registered.v1")
                .routingKey("user.registered.v1")
                .payload(payload)
                .status(OutboxEventStatus.PENDING)
                .attemptCount(0)
                .nextRetryAt(now)
                .createdAt(now)
                .traceParent(traceParent)
                .traceState(traceState)
                .build();
    }
}
