package com.app.common.analytics.observability;

import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.app.common.analytics.ingest.AnalyticsIngestionController;
import com.app.common.analytics.ingest.AnalyticsListenerIds;
import com.app.common.analytics.migration.AnalyticsSchemaGate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The analytics store's meters, scraped with everything else on the management port.
 *
 * <p>Prometheus names: {@code luvax_analytics_user_events_dropped_total{reason}}, {@code
 * luvax_analytics_audit_log_fallback_total}, {@code luvax_analytics_ingestion_running{listener}}
 * and {@code luvax_analytics_schema_ready}. The two gauges read the schema gate and the ingestion
 * controller lazily, and report 0 when analytics is switched off and those beans do not exist.
 */
@Component
public class AnalyticsMetrics {

    /** Why the behavioural event recorder dropped a row instead of writing it. */
    public enum DropReason {
        PERMITS,
        NOT_READY,
        CIRCUIT_OPEN,
        ERROR
    }

    private final MeterRegistry registry;

    // Held strongly: a gauge references its source object weakly, so a provider referenced only by
    // the gauge would be collected and the gauge would read NaN.
    private final ObjectProvider<AnalyticsSchemaGate> schemaGate;
    private final ObjectProvider<AnalyticsIngestionController> ingestionController;

    public AnalyticsMetrics(
            MeterRegistry registry,
            ObjectProvider<AnalyticsSchemaGate> schemaGate,
            ObjectProvider<AnalyticsIngestionController> ingestionController) {
        this.registry = registry;
        this.schemaGate = schemaGate;
        this.ingestionController = ingestionController;

        // Every reason is registered up front so each series exists at zero and an increase() over
        // it is defined before the first drop.
        for (DropReason reason : DropReason.values()) {
            droppedCounter(reason);
        }
        Counter.builder("luvax.analytics.audit_log.fallback")
                .description("Audit log pages served from PostgreSQL because ClickHouse failed")
                .register(registry);

        Gauge.builder(
                        "luvax.analytics.schema.ready",
                        this.schemaGate,
                        provider -> {
                            AnalyticsSchemaGate gate = provider.getIfAvailable();
                            return gate != null && gate.isReady() ? 1 : 0;
                        })
                .description("1 once every ClickHouse migration has applied in this process")
                .register(registry);

        for (String listenerId : AnalyticsListenerIds.ALL) {
            Gauge.builder(
                            "luvax.analytics.ingestion.running",
                            this.ingestionController,
                            provider -> {
                                AnalyticsIngestionController controller = provider.getIfAvailable();
                                return controller != null && controller.isRunning(listenerId)
                                        ? 1
                                        : 0;
                            })
                    .description("1 while the analytics listener container is consuming")
                    .tag("listener", listenerId)
                    .register(registry);
        }
    }

    /**
     * Counts one behavioural event dropped instead of written.
     *
     * @param reason why it was dropped
     */
    public void recordUserEventDropped(DropReason reason) {
        droppedCounter(reason).increment();
    }

    /** Counts one audit log page served from PostgreSQL because ClickHouse was unavailable. */
    public void recordAuditLogFallback() {
        registry.counter("luvax.analytics.audit_log.fallback").increment();
    }

    private Counter droppedCounter(DropReason reason) {
        return Counter.builder("luvax.analytics.user_events.dropped")
                .description("Behavioural events dropped instead of written to ClickHouse")
                .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }
}
