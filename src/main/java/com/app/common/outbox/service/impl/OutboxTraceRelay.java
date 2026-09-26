package com.app.common.outbox.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.app.common.observability.W3cTraceContext;
import com.app.common.outbox.entity.OutboxEvent;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

/**
 * Restores an outbox event's stored trace context around the broker send.
 *
 * <p>The send is observed by RabbitTemplate, and Micrometer Tracing parents a sender span on the
 * current span when it differs from the parent observation's span. Making the stored context
 * current therefore turns the send, and every consumer that extracts its header, into descendants
 * of the request that wrote the event, while the publisher's own span stays in the publisher's
 * trace and links to it.
 */
@Component
class OutboxTraceRelay {

    private static final TextMapGetter<OutboxEvent> GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(OutboxEvent carrier) {
                    return List.of(W3cTraceContext.TRACEPARENT, W3cTraceContext.TRACESTATE);
                }

                @Override
                public String get(OutboxEvent carrier, String key) {
                    if (carrier == null) {
                        return null;
                    }
                    return switch (key) {
                        case W3cTraceContext.TRACEPARENT -> carrier.getTraceParent();
                        case W3cTraceContext.TRACESTATE -> carrier.getTraceState();
                        default -> null;
                    };
                }
            };

    private final Tracer tracer;

    OutboxTraceRelay(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Makes the event's stored origin span current for the duration of the returned scope, so a
     * broker send observed inside it is parented on that origin.
     *
     * @param event the event whose stored trace context is restored
     * @return a scope to close after the send; a no-op scope when the event carries no context
     */
    Scope makeOriginCurrent(OutboxEvent event) {
        Optional<SpanContext> origin = originOf(event);
        if (origin.isEmpty()) {
            return Scope.noop();
        }
        return Context.root()
                .with(io.opentelemetry.api.trace.Span.wrap(origin.get()))
                .makeCurrent();
    }

    /**
     * Builds a link to the event's stored origin span, for the publisher's own relay span.
     *
     * @param event the event whose stored trace context becomes the link target
     * @return the link, or empty when the event carries no context
     */
    Optional<Link> originLink(OutboxEvent event) {
        return originOf(event)
                .map(
                        origin ->
                                new Link(
                                        tracer.traceContextBuilder()
                                                .traceId(origin.getTraceId())
                                                .spanId(origin.getSpanId())
                                                .sampled(origin.isSampled())
                                                .build()));
    }

    private Optional<SpanContext> originOf(OutboxEvent event) {
        if (event.getTraceParent() == null) {
            return Optional.empty();
        }
        SpanContext spanContext =
                io.opentelemetry.api.trace.Span.fromContext(
                                W3CTraceContextPropagator.getInstance()
                                        .extract(Context.root(), event, GETTER))
                        .getSpanContext();
        return spanContext.isValid() ? Optional.of(spanContext) : Optional.empty();
    }
}
