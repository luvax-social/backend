package com.app.common.observability;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;

/**
 * Observation filtering and meter bindings that Spring Boot does not provide on its own.
 *
 * <p>Resilience4j 2.3.0's metrics auto-configuration orders itself after a Spring Boot 3 class name
 * that no longer exists in Spring Boot 4, so circuit breaker meters are bound here explicitly; an
 * identical meter registered twice resolves to the same meter.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    ObservationPredicate noiseObservationPredicate(ObjectProvider<Tracer> tracerProvider) {
        return new NoiseObservationPredicate(tracerProvider);
    }

    @Bean
    MeterBinder circuitBreakerMetrics(CircuitBreakerRegistry circuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }
}
