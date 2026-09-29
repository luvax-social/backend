package com.app.modules.recommendation.rebuild;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The Gorse rebuild's meters, scraped with everything else on the management port.
 *
 * <p>Prometheus names: {@code luvax_gorse_rebuild_phase} (0 idle, 1 to 6 the phases in order, 7
 * done, -1 failed), {@code luvax_gorse_rebuild_sent_total{kind="users|items|feedback"}}, {@code
 * luvax_gorse_rebuild_skipped_total{reason="unknown_post"}} and {@code
 * luvax_gorse_rebuild_verification_failures_total}. All exist at zero from startup, so a dashboard
 * built on them is defined before the first rebuild.
 */
@Component
public class GorseRebuildMetrics {

    /** What a batch handed to Gorse. */
    public enum Kind {
        USERS("users"),
        ITEMS("items"),
        FEEDBACK("feedback");

        private final String tag;

        Kind(String tag) {
            this.tag = tag;
        }
    }

    private static final int IDLE = 0;
    private static final int FAILED = -1;

    private final AtomicInteger phase = new AtomicInteger(IDLE);
    private final Counter users;
    private final Counter items;
    private final Counter feedback;
    private final Counter skippedUnknownPost;
    private final Counter verificationFailures;

    public GorseRebuildMetrics(MeterRegistry registry) {
        Gauge.builder("luvax.gorse.rebuild.phase", phase, AtomicInteger::get)
                .description("Current Gorse rebuild phase: 0 idle, 1-6 phases, 7 done, -1 failed")
                .register(registry);
        users = sent(registry, Kind.USERS);
        items = sent(registry, Kind.ITEMS);
        feedback = sent(registry, Kind.FEEDBACK);
        skippedUnknownPost =
                Counter.builder("luvax.gorse.rebuild.skipped")
                        .tag("reason", "unknown_post")
                        .description("Feedback tuples not sent because PostgreSQL has no such post")
                        .register(registry);
        verificationFailures =
                Counter.builder("luvax.gorse.rebuild.verification.failures")
                        .description("Rebuilds whose final comparison with PostgreSQL found a gap")
                        .register(registry);
    }

    private static Counter sent(MeterRegistry registry, Kind kind) {
        return Counter.builder("luvax.gorse.rebuild.sent")
                .tag("kind", kind.tag)
                .description("Rows a Gorse rebuild has pushed")
                .register(registry);
    }

    public void entered(GorseRebuildPhase current) {
        phase.set(current.gaugeValue());
    }

    public void failed() {
        phase.set(FAILED);
    }

    public void idle() {
        phase.set(IDLE);
    }

    public void sent(Kind kind, long count) {
        switch (kind) {
            case USERS -> users.increment(count);
            case ITEMS -> items.increment(count);
            case FEEDBACK -> feedback.increment(count);
        }
    }

    public void skippedUnknownPost(long count) {
        skippedUnknownPost.increment(count);
    }

    public void verificationFailed() {
        verificationFailures.increment();
    }
}
