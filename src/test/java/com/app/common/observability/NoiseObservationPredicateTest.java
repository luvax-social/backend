package com.app.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import com.app.common.outbox.observability.OutboxMetricsSampler;
import com.app.common.outbox.service.impl.OutboxPublisherServiceImpl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

@ExtendWith(MockitoExtension.class)
class NoiseObservationPredicateTest {

    @Mock private ObjectProvider<Tracer> tracerProvider;
    @Mock private Tracer tracer;
    @Mock private Span span;

    private NoiseObservationPredicate predicate;

    @BeforeEach
    void setUp() {
        predicate = new NoiseObservationPredicate(tracerProvider);
    }

    @Test
    void test_actuatorPrometheusUri_dropped() {
        assertThat(predicate.test("http.server.requests", serverContext("/actuator/prometheus")))
                .isFalse();
    }

    @Test
    void test_actuatorHealthUri_dropped() {
        assertThat(predicate.test("http.server.requests", serverContext("/actuator/health")))
                .isFalse();
    }

    @Test
    void test_applicationUri_kept() {
        assertThat(predicate.test("http.server.requests", serverContext("/api/v1/posts"))).isTrue();
    }

    @Test
    void test_outboxPublisherScheduledContext_dropped() throws NoSuchMethodException {
        assertThat(
                        predicate.test(
                                "tasks.scheduled.execution",
                                scheduledContext(
                                        new OutboxPublisherServiceImpl(
                                                null, null, null, null, null, null, null))))
                .isFalse();
    }

    @Test
    void test_outboxMetricsSamplerScheduledContext_dropped() throws NoSuchMethodException {
        assertThat(predicate.test("tasks.scheduled.execution", scheduledContext(sampler())))
                .isFalse();
    }

    @Test
    void test_otherScheduledJob_kept() throws NoSuchMethodException {
        assertThat(
                        predicate.test(
                                "tasks.scheduled.execution", scheduledContext(new NotOutboxJob())))
                .isTrue();
    }

    @Test
    void test_jdbcObservation_droppedWithNoCurrentSpan() {
        when(tracerProvider.getIfAvailable()).thenReturn(tracer);
        when(tracer.currentSpan()).thenReturn(null);
        assertThat(predicate.test("jdbc.query", new Observation.Context())).isFalse();
    }

    @Test
    void test_jdbcObservation_keptWithCurrentSpan() {
        when(tracerProvider.getIfAvailable()).thenReturn(tracer);
        when(tracer.currentSpan()).thenReturn(span);
        assertThat(predicate.test("jdbc.query", new Observation.Context())).isTrue();
    }

    private static ServerRequestObservationContext serverContext(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }

    private static ScheduledTaskObservationContext scheduledContext(Object target)
            throws NoSuchMethodException {
        Method marker = NotOutboxJob.class.getDeclaredMethod("run");
        return new ScheduledTaskObservationContext(target, marker);
    }

    private static OutboxMetricsSampler sampler() {
        return new OutboxMetricsSampler(null, new SimpleMeterRegistry());
    }

    private static class NotOutboxJob {
        void run() {}
    }
}
