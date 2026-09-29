package com.app.common.analytics.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.app.common.analytics.ingest.AnalyticsIngestionController;
import com.app.common.analytics.ingest.AnalyticsListenerIds;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.analytics.observability.AnalyticsMetrics.DropReason;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AnalyticsMetricsTest {

    private SimpleMeterRegistry registry;
    private AnalyticsSchemaGate gate;
    private AnalyticsIngestionController controller;
    private AnalyticsMetrics metrics;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        gate = mock(AnalyticsSchemaGate.class);
        controller = mock(AnalyticsIngestionController.class);
        ObjectProvider<AnalyticsSchemaGate> gateProvider = mock(ObjectProvider.class);
        when(gateProvider.getIfAvailable()).thenReturn(gate);
        ObjectProvider<AnalyticsIngestionController> controllerProvider =
                mock(ObjectProvider.class);
        when(controllerProvider.getIfAvailable()).thenReturn(controller);
        metrics = new AnalyticsMetrics(registry, gateProvider, controllerProvider);
    }

    private double dropped(String reason) {
        return registry.get("luvax.analytics.user_events.dropped")
                .tag("reason", reason)
                .counter()
                .count();
    }

    @Test
    void constructor_registersEveryDropReasonAtZero() {
        for (String reason : new String[] {"permits", "not_ready", "circuit_open", "error"}) {
            assertThat(dropped(reason)).isZero();
        }
    }

    @Test
    void recordUserEventDropped_incrementsOnlyThatReason() {
        metrics.recordUserEventDropped(DropReason.CIRCUIT_OPEN);
        metrics.recordUserEventDropped(DropReason.CIRCUIT_OPEN);
        metrics.recordUserEventDropped(DropReason.PERMITS);

        assertThat(dropped("circuit_open")).isEqualTo(2);
        assertThat(dropped("permits")).isEqualTo(1);
        assertThat(dropped("error")).isZero();
    }

    @Test
    void recordAuditLogFallback_incrementsTheFallbackCounter() {
        metrics.recordAuditLogFallback();

        assertThat(registry.get("luvax.analytics.audit_log.fallback").counter().count())
                .isEqualTo(1);
    }

    @Test
    void schemaReadyGauge_followsTheGate() {
        assertThat(registry.get("luvax.analytics.schema.ready").gauge().value()).isZero();

        when(gate.isReady()).thenReturn(true);

        assertThat(registry.get("luvax.analytics.schema.ready").gauge().value()).isEqualTo(1);
    }

    @Test
    void ingestionRunningGauge_isPerListenerAndFollowsTheController() {
        when(controller.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).thenReturn(true);

        assertThat(
                        registry.get("luvax.analytics.ingestion.running")
                                .tag("listener", AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)
                                .gauge()
                                .value())
                .isEqualTo(1);
        assertThat(
                        registry.get("luvax.analytics.ingestion.running")
                                .tag("listener", AnalyticsListenerIds.PLATFORM_STATS_INGEST)
                                .gauge()
                                .value())
                .isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void gauges_analyticsSwitchedOff_reportZero() {
        SimpleMeterRegistry offRegistry = new SimpleMeterRegistry();
        ObjectProvider<AnalyticsSchemaGate> noGate = mock(ObjectProvider.class);
        ObjectProvider<AnalyticsIngestionController> noController = mock(ObjectProvider.class);

        new AnalyticsMetrics(offRegistry, noGate, noController);

        assertThat(offRegistry.get("luvax.analytics.schema.ready").gauge().value()).isZero();
        assertThat(
                        offRegistry
                                .get("luvax.analytics.ingestion.running")
                                .tag("listener", AnalyticsListenerIds.USER_EVENT_IMPORT)
                                .gauge()
                                .value())
                .isZero();
    }
}
