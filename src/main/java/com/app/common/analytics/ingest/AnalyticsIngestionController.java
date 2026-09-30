package com.app.common.analytics.ingest;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.migration.AnalyticsSchemaGate;
import com.app.common.analytics.migration.AnalyticsSchemaReadyEvent;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Starts and stops the analytics listener containers so messages wait in RabbitMQ, instead of
 * dead-lettering, while ClickHouse cannot take them.
 *
 * <p>A container runs only while the schema gate is ready and the {@code clickhouse} circuit
 * breaker is not open. When the breaker opens the containers stop, and the broker requeues their
 * unacknowledged messages; when it half-opens they start again, so the half-open trial calls are
 * real work rather than synthetic probes.
 *
 * <p>Transitions are handled on this class's own single thread, never on the listener thread that
 * tripped the breaker, because stopping a container from one of its own consumer threads waits on
 * itself.
 *
 * <p>Independently of the breaker, {@link #suspend(String)} holds the recommendation feedback
 * listener stopped, which the Gorse rebuild needs so no live feedback interleaves with it.
 */
@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class AnalyticsIngestionController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsIngestionController.class);

    private final ObjectProvider<RabbitListenerEndpointRegistry> registryProvider;
    private final CircuitBreaker circuitBreaker;
    private final AnalyticsSchemaGate schemaGate;
    private final Executor transitionExecutor;
    private final ExecutorService ownedExecutor;
    private final Set<String> suspensions = ConcurrentHashMap.newKeySet();

    @Autowired
    public AnalyticsIngestionController(
            ObjectProvider<RabbitListenerEndpointRegistry> registryProvider,
            CircuitBreakerRegistry circuitBreakerRegistry,
            AnalyticsSchemaGate schemaGate) {
        this(
                registryProvider,
                circuitBreakerRegistry,
                schemaGate,
                Executors.newSingleThreadExecutor(
                        task -> {
                            Thread thread = new Thread(task, "analytics-ingestion-control");
                            thread.setDaemon(true);
                            return thread;
                        }));
    }

    private AnalyticsIngestionController(
            ObjectProvider<RabbitListenerEndpointRegistry> registryProvider,
            CircuitBreakerRegistry circuitBreakerRegistry,
            AnalyticsSchemaGate schemaGate,
            ExecutorService ownedExecutor) {
        this(registryProvider, circuitBreakerRegistry, schemaGate, ownedExecutor, ownedExecutor);
    }

    AnalyticsIngestionController(
            ObjectProvider<RabbitListenerEndpointRegistry> registryProvider,
            CircuitBreakerRegistry circuitBreakerRegistry,
            AnalyticsSchemaGate schemaGate,
            Executor transitionExecutor,
            ExecutorService ownedExecutor) {
        this.registryProvider = registryProvider;
        this.circuitBreaker =
                circuitBreakerRegistry.circuitBreaker(
                        ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME);
        this.schemaGate = schemaGate;
        this.transitionExecutor = transitionExecutor;
        this.ownedExecutor = ownedExecutor;
    }

    @PostConstruct
    void subscribeToBreaker() {
        circuitBreaker
                .getEventPublisher()
                .onStateTransition(
                        event -> {
                            log.info(
                                    "[analytics] clickhouse breaker {}",
                                    event.getStateTransition());
                            transitionExecutor.execute(this::reconcile);
                        });
    }

    @EventListener(AnalyticsSchemaReadyEvent.class)
    void onSchemaReady() {
        transitionExecutor.execute(this::reconcile);
    }

    /**
     * Holds the recommendation feedback listener stopped until every suspension is resumed,
     * whatever the breaker does. Returns after the container has stopped, so the caller can rely on
     * no live feedback being applied from then on.
     *
     * @param reason names the holder, so two holders do not release each other's suspension
     */
    public void suspend(String reason) {
        suspensions.add(reason);
        reconcile();
    }

    /**
     * Releases a suspension. The feedback listener starts again only when no suspension is left and
     * the schema gate and breaker allow it.
     *
     * @param reason the name passed to {@link #suspend(String)}
     */
    public void resume(String reason) {
        suspensions.remove(reason);
        reconcile();
    }

    /**
     * Reports whether a listener container currently runs.
     *
     * @param listenerId one of {@link AnalyticsListenerIds#ALL}
     * @return false when the container does not exist or is stopped
     */
    public boolean isRunning(String listenerId) {
        RabbitListenerEndpointRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return false;
        }
        MessageListenerContainer container = registry.getListenerContainer(listenerId);
        return container != null && container.isRunning();
    }

    synchronized void reconcile() {
        RabbitListenerEndpointRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        boolean permitted = schemaGate.isReady() && breakerAllowsCalls();
        for (String id : AnalyticsListenerIds.ALL) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            if (container == null) {
                continue;
            }
            boolean shouldRun = permitted && !isSuspended(id);
            try {
                if (shouldRun && !container.isRunning()) {
                    container.start();
                    log.info("[analytics] started listener {}", id);
                } else if (!shouldRun && container.isRunning()) {
                    container.stop();
                    log.info("[analytics] stopped listener {}", id);
                }
            } catch (RuntimeException e) {
                log.warn("[analytics] could not change the state of listener {}", id, e);
            }
        }
    }

    // Blocks until every transition submitted before this call has been handled, so a test does not
    // have to poll for a container to stop.
    void awaitIdle(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        transitionExecutor.execute(() -> done.complete(null));
        done.get(timeout, unit);
    }

    private boolean breakerAllowsCalls() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state != CircuitBreaker.State.OPEN && state != CircuitBreaker.State.FORCED_OPEN;
    }

    private boolean isSuspended(String listenerId) {
        return AnalyticsListenerIds.RECOMMENDATION_FEEDBACK.equals(listenerId)
                && !suspensions.isEmpty();
    }

    @PreDestroy
    void stop() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }
}
