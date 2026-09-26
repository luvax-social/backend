package com.app.common.observability;

import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.util.ClassUtils;

import com.app.common.outbox.observability.OutboxMetricsSampler;
import com.app.common.outbox.service.impl.OutboxPublisherServiceImpl;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;

/**
 * Drops observations that would otherwise flood the trace store without describing user work.
 *
 * <p>Actuator traffic is the health check and the Prometheus scrape. The outbox poller runs every
 * second and the outbox sampler every thirty seconds, so each would open a root trace per run; the
 * poller opens its own batch observation once a claim returns rows. A JDBC observation with no
 * current span belongs to no trace, which is the empty claim query and startup SQL.
 */
class NoiseObservationPredicate implements ObservationPredicate {

    private static final Set<Class<?>> UNTRACED_SCHEDULED_TYPES =
            Set.of(OutboxPublisherServiceImpl.class, OutboxMetricsSampler.class);

    private final ObjectProvider<Tracer> tracerProvider;

    NoiseObservationPredicate(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext server) {
            String uri = server.getCarrier().getRequestURI();
            return uri == null || !uri.startsWith("/actuator");
        }
        if (context instanceof ScheduledTaskObservationContext scheduled) {
            return !UNTRACED_SCHEDULED_TYPES.contains(
                    ClassUtils.getUserClass(scheduled.getTargetClass()));
        }
        if (name.startsWith("jdbc.")) {
            Tracer tracer = tracerProvider.getIfAvailable();
            return tracer != null && tracer.currentSpan() != null;
        }
        return true;
    }
}
