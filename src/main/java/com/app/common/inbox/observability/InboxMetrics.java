package com.app.common.inbox.observability;

import java.util.Locale;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Counts inbox outcomes: how many messages a consumer processed versus rejected as a duplicate. */
@Component
public class InboxMetrics {

    /** Whether a consumer's inbox check found a duplicate or processed the message. */
    public enum Outcome {
        PROCESSED,
        DUPLICATE
    }

    private final MeterRegistry registry;

    public InboxMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Increments the counter for one consumer's outcome.
     *
     * @param consumerName the consumer that ran the check
     * @param outcome whether the message was processed or found to be a duplicate
     */
    public void record(String consumerName, Outcome outcome) {
        Counter.builder("luvax.inbox.messages")
                .tag("consumer", consumerName)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment();
    }
}
