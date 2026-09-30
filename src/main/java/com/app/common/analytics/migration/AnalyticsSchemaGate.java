package com.app.common.analytics.migration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.analytics.config.AnalyticsProperties;

/**
 * Runs the ClickHouse schema runner once the application is up, and again every retry interval
 * until it succeeds, then reports ready.
 *
 * <p>The application starts and serves while ClickHouse is down: until this gate is ready, every
 * {@code ClickHouseOperations} call fails as unavailable without touching the circuit breaker, and
 * the analytics listener containers stay stopped. Ready is published once as an {@link
 * AnalyticsSchemaReadyEvent}.
 */
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class AnalyticsSchemaGate {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsSchemaGate.class);

    private final ClickHouseMigrationRunner runner;
    private final ApplicationEventPublisher eventPublisher;
    private final AnalyticsProperties properties;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "analytics-schema-gate");
                        thread.setDaemon(true);
                        return thread;
                    });

    private volatile boolean ready;
    private volatile ScheduledFuture<?> schedule;

    public AnalyticsSchemaGate(
            ClickHouseMigrationRunner runner,
            ApplicationEventPublisher eventPublisher,
            AnalyticsProperties properties) {
        this.runner = runner;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * Reports whether every ClickHouse migration has applied in this process.
     *
     * @return true once a run has succeeded; it never goes back to false
     */
    public boolean isReady() {
        return ready;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        long intervalMillis = properties.migration().retryInterval().toMillis();
        schedule =
                executor.scheduleWithFixedDelay(
                        this::attemptQuietly, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs the migrations now if the gate is not ready yet.
     *
     * @return whether the gate is ready afterwards
     * @throws RuntimeException the runner's failure, when the run does not succeed
     */
    public synchronized boolean attempt() {
        if (ready) {
            return true;
        }
        runner.migrate();
        ready = true;
        log.info("[analytics] clickhouse schema ready");
        eventPublisher.publishEvent(new AnalyticsSchemaReadyEvent());
        return true;
    }

    private void attemptQuietly() {
        try {
            if (attempt()) {
                ScheduledFuture<?> current = schedule;
                if (current != null) {
                    current.cancel(false);
                }
            }
        } catch (RuntimeException e) {
            log.warn(
                    "[analytics] clickhouse schema not ready, will retry in {}: {}",
                    properties.migration().retryInterval(),
                    e.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
