package com.app.common.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/** Serialises the current span's context into W3C form for storage alongside an event. */
@Component
public class TraceContextCapture {

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContextCapture(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * Captures the currently active span's trace context, if any.
     *
     * @return the current context in W3C form, or empty when no span is active
     */
    public Optional<W3cTraceContext> captureCurrent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return Optional.empty();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return W3cTraceContext.fromCarrier(carrier);
    }
}
