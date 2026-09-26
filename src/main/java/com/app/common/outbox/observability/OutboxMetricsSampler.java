package com.app.common.outbox.observability;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Samples the outbox table into gauges every thirty seconds by default.
 *
 * <p>Every sub-select in {@code SAMPLE_SQL} is served by a partial index: {@code
 * idx_outbox_events_publish_scan} for PENDING and PROCESSING, and the two retention indexes added
 * alongside the purge job for PUBLISHED and DEAD. A query failure leaves the previous values in
 * place and logs once, rather than resetting the gauges to zero.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.outbox.metrics",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OutboxMetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetricsSampler.class);

    private static final String SAMPLE_SQL =
            """
			SELECT
				(SELECT count(*) FROM outbox_events WHERE status = 'PENDING')    AS pending,
				(SELECT count(*) FROM outbox_events WHERE status = 'PROCESSING') AS processing,
				(SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED')  AS published,
				(SELECT count(*) FROM outbox_events WHERE status = 'DEAD')       AS dead,
				(SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0)
				FROM outbox_events WHERE status = 'PENDING')                  AS oldest_pending_age_seconds
			""";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final MultiGauge eventsByStatus;
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public OutboxMetricsSampler(NamedParameterJdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventsByStatus = MultiGauge.builder("luvax.outbox.events").register(registry);
        Gauge.builder("luvax.outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::get)
                .baseUnit("seconds")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.metrics.sample-interval:PT30S}")
    void sample() {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(SAMPLE_SQL, Map.of());
            eventsByStatus.register(
                    List.of(
                            MultiGauge.Row.of(statusTags("PENDING"), asLong(row, "pending")),
                            MultiGauge.Row.of(statusTags("PROCESSING"), asLong(row, "processing")),
                            MultiGauge.Row.of(statusTags("PUBLISHED"), asLong(row, "published")),
                            MultiGauge.Row.of(statusTags("DEAD"), asLong(row, "dead"))),
                    true);
            oldestPendingAgeSeconds.set(asLong(row, "oldest_pending_age_seconds"));
        } catch (RuntimeException ex) {
            log.error("Failed to sample outbox metrics; previous values retained", ex);
        }
    }

    private static long asLong(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static Tags statusTags(String status) {
        return Tags.of(Tag.of("status", status));
    }
}
