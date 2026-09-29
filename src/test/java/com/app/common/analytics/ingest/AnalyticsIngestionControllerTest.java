package com.app.common.analytics.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;

import com.app.common.analytics.impl.ClickHouseOperationsImpl;
import com.app.common.analytics.migration.AnalyticsSchemaGate;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

class AnalyticsIngestionControllerTest {

    private AnalyticsSchemaGate gate;
    private CircuitBreaker breaker;
    private RabbitListenerEndpointRegistry registry;
    private final Map<String, AtomicBoolean> running = new HashMap<>();
    private AnalyticsIngestionController controller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        gate = mock(AnalyticsSchemaGate.class);
        when(gate.isReady()).thenReturn(false);
        breaker =
                CircuitBreakerRegistry.ofDefaults()
                        .circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME);
        CircuitBreakerRegistry breakers = mock(CircuitBreakerRegistry.class);
        when(breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME))
                .thenReturn(breaker);

        registry = mock(RabbitListenerEndpointRegistry.class);
        // The feedback consumer is conditional, so one listener has no container at all.
        for (String id :
                List.of(
                        AnalyticsListenerIds.ADMIN_ACTION_REPLICATION,
                        AnalyticsListenerIds.PLATFORM_STATS_INGEST,
                        AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)) {
            MessageListenerContainer created = container(id);
            when(registry.getListenerContainer(id)).thenReturn(created);
        }
        ObjectProvider<RabbitListenerEndpointRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        // Runnable::run handles every transition on the calling thread.
        controller =
                new AnalyticsIngestionController(provider, breakers, gate, Runnable::run, null);
        controller.subscribeToBreaker();
    }

    private MessageListenerContainer container(String id) {
        AtomicBoolean state = new AtomicBoolean(false);
        running.put(id, state);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenAnswer(invocation -> state.get());
        doAnswer(
                        invocation -> {
                            state.set(true);
                            return null;
                        })
                .when(container)
                .start();
        doAnswer(
                        invocation -> {
                            state.set(false);
                            return null;
                        })
                .when(container)
                .stop();
        return container;
    }

    private boolean isRunning(String id) {
        return running.get(id).get();
    }

    private void schemaReady() {
        when(gate.isReady()).thenReturn(true);
        controller.onSchemaReady();
    }

    @Test
    void onSchemaReady_startsEveryContainerThatExists() {
        schemaReady();

        assertThat(isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
        assertThat(isRunning(AnalyticsListenerIds.PLATFORM_STATS_INGEST)).isTrue();
        assertThat(isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isTrue();
        assertThat(controller.isRunning(AnalyticsListenerIds.USER_EVENT_IMPORT)).isFalse();
    }

    @Test
    void breakerOpen_stopsEveryContainer() {
        schemaReady();

        breaker.transitionToOpenState();

        assertThat(running.values()).noneMatch(AtomicBoolean::get);
    }

    @Test
    void breakerForcedOpen_stopsEveryContainer() {
        schemaReady();

        breaker.transitionToForcedOpenState();

        assertThat(running.values()).noneMatch(AtomicBoolean::get);
    }

    @Test
    void breakerHalfOpen_startsContainersSoTheTrialCallsAreRealWork() {
        schemaReady();
        breaker.transitionToOpenState();

        breaker.transitionToHalfOpenState();

        assertThat(running.values()).allMatch(AtomicBoolean::get);
    }

    @Test
    void breakerClosed_startsContainersAgain() {
        schemaReady();
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        breaker.transitionToClosedState();

        assertThat(running.values()).allMatch(AtomicBoolean::get);
    }

    @Test
    void breakerHalfOpen_schemaNotReady_startsNothing() {
        breaker.transitionToOpenState();

        breaker.transitionToHalfOpenState();

        assertThat(running.values()).noneMatch(AtomicBoolean::get);
    }

    @Test
    void onSchemaReady_breakerAlreadyOpen_startsNothing() {
        breaker.transitionToOpenState();

        schemaReady();

        assertThat(running.values()).noneMatch(AtomicBoolean::get);
    }

    @Test
    void suspend_stopsOnlyTheFeedbackListener() {
        schemaReady();

        controller.suspend("gorse-rebuild");

        assertThat(isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isFalse();
        assertThat(isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();
        assertThat(isRunning(AnalyticsListenerIds.PLATFORM_STATS_INGEST)).isTrue();
    }

    @Test
    void suspend_winsOverABreakerCloseUntilResume() {
        schemaReady();
        controller.suspend("gorse-rebuild");

        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        breaker.transitionToClosedState();

        assertThat(isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isFalse();
        assertThat(isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION)).isTrue();

        controller.resume("gorse-rebuild");

        assertThat(isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isTrue();
    }

    @Test
    void resume_anotherHolderStillSuspended_keepsTheFeedbackListenerStopped() {
        schemaReady();
        controller.suspend("gorse-rebuild");
        controller.suspend("maintenance");

        controller.resume("gorse-rebuild");

        assertThat(isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isFalse();

        controller.resume("maintenance");

        assertThat(isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isTrue();
    }

    @Test
    void resume_breakerOpen_doesNotStartTheFeedbackListener() {
        schemaReady();
        controller.suspend("gorse-rebuild");
        breaker.transitionToOpenState();

        controller.resume("gorse-rebuild");

        assertThat(isRunning(AnalyticsListenerIds.RECOMMENDATION_FEEDBACK)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcile_noRabbitRegistry_isANoOp() {
        ObjectProvider<RabbitListenerEndpointRegistry> absent = mock(ObjectProvider.class);
        when(absent.getIfAvailable()).thenReturn(null);
        CircuitBreakerRegistry breakers = mock(CircuitBreakerRegistry.class);
        when(breakers.circuitBreaker(ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME))
                .thenReturn(breaker);
        AnalyticsIngestionController withoutRabbit =
                new AnalyticsIngestionController(absent, breakers, gate, Runnable::run, null);

        withoutRabbit.onSchemaReady();
        withoutRabbit.suspend("gorse-rebuild");

        assertThat(withoutRabbit.isRunning(AnalyticsListenerIds.ADMIN_ACTION_REPLICATION))
                .isFalse();
    }
}
