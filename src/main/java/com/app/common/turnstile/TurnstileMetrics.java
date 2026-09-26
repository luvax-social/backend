package com.app.common.turnstile;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer instrumentation for Cloudflare Turnstile verification.
 *
 * <p>Exists so that a Cloudflare outage on the auth surfaces is visible rather than silent. Those
 * surfaces fail open, which means an outage produces no errors and no failed logins - without a
 * counter the only symptom would be bot traffic nobody noticed the control had stopped stopping.
 */
@Component
public class TurnstileMetrics {

    private static final String COUNTER = "turnstile.verification.total";

    private final MeterRegistry registry;
    private final Timer duration;

    public TurnstileMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.duration = registry.timer("turnstile.verification.duration");
    }

    void record(TurnstileSurface surface, TurnstileOutcome outcome, long elapsedNanos) {
        registry.counter(COUNTER, "outcome", outcome.tag(), "surface", surface.tag()).increment();
        duration.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }
}
