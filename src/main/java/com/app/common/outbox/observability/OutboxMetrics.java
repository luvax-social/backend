package com.app.common.outbox.observability;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Timers for a single outbox publish attempt and for the delay between write and publish. */
@Component
public class OutboxMetrics {

    /** How a publish attempt ended. */
    public enum Outcome {
        PUBLISHED,
        RETRY_SCHEDULED,
        DEAD;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Duration[] SLO =
            new Duration[] {
                Duration.ofMillis(10),
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2)
            };

    private final MeterRegistry registry;
    private final Timer publishLag;

    public OutboxMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.publishLag =
                Timer.builder("luvax.outbox.publish.lag")
                        .publishPercentileHistogram()
                        .serviceLevelObjectives(SLO)
                        .register(registry);
    }

    /**
     * Starts timing one publish attempt.
     *
     * @return a sample to pass to {@link #stopPublish(Timer.Sample, Outcome)}
     */
    public Timer.Sample startPublish() {
        return Timer.start(registry);
    }

    /**
     * Stops timing a publish attempt and records it under its outcome.
     *
     * @param sample the sample returned by {@link #startPublish()}
     * @param outcome how the attempt ended
     */
    public void stopPublish(Timer.Sample sample, Outcome outcome) {
        Timer timer =
                Timer.builder("luvax.outbox.publish")
                        .tag("outcome", outcome.tag())
                        .publishPercentileHistogram()
                        .serviceLevelObjectives(SLO)
                        .register(registry);
        sample.stop(timer);
    }

    /**
     * Records the delay between an event's write and its publish.
     *
     * @param createdAt when the event was written
     * @param publishedAt when the publish attempt ran
     */
    public void recordLag(OffsetDateTime createdAt, OffsetDateTime publishedAt) {
        publishLag.record(Duration.between(createdAt, publishedAt));
    }
}
