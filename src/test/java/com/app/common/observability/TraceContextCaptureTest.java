package com.app.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/** Exercises {@link TraceContextCapture} against a real, minimal OTel SDK tracer. */
class TraceContextCaptureTest {

    private static AnnotationConfigApplicationContext context;
    private static Tracer tracer;
    private static Propagator propagator;

    @BeforeAll
    static void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.register(
                OpenTelemetrySdkAutoConfiguration.class,
                OpenTelemetryTracingAutoConfiguration.class);
        context.refresh();
        tracer = context.getBean(Tracer.class);
        propagator = context.getBean(Propagator.class);
    }

    @AfterAll
    static void tearDown() {
        context.close();
    }

    @Test
    void captureCurrent_insideSpan_returnsThatSpansContext() {
        TraceContextCapture capture = new TraceContextCapture(tracer, propagator);
        Span span = tracer.nextSpan().name("test-span").start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            var result = capture.captureCurrent();

            assertThat(result).isPresent();
            assertThat(result.get().traceParent()).contains(span.context().traceId());
            assertThat(result.get().traceParent()).contains(span.context().spanId());
        } finally {
            span.end();
        }
    }

    @Test
    void captureCurrent_outsideAnySpan_isEmpty() {
        TraceContextCapture capture = new TraceContextCapture(tracer, propagator);

        assertThat(capture.captureCurrent()).isEmpty();
    }
}
