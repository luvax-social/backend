package com.app.common.analytics.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.app.common.analytics.ClickHouseRequestRejectedException;
import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.ClickHouseUnavailableException.Reason;
import com.app.common.analytics.migration.AnalyticsSchemaGate;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.ObservationRegistry;

class ClickHouseOperationsImplTest {

    private AnalyticsSchemaGate gate;
    private CircuitBreaker breaker;
    private JdbcClient writer;
    private JdbcClient reader;
    private JdbcClient batch;
    private ClickHouseOperationsImpl operations;

    @BeforeEach
    void setUp() {
        gate = mock(AnalyticsSchemaGate.class);
        when(gate.isReady()).thenReturn(true);
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        // Same shape as the clickhouse instance in resilience4j-dev.yml.
        breaker =
                registry.circuitBreaker(
                        ClickHouseOperationsImpl.CIRCUIT_BREAKER_NAME,
                        CircuitBreakerConfig.custom()
                                .slidingWindowSize(10)
                                .minimumNumberOfCalls(5)
                                .failureRateThreshold(50)
                                .recordExceptions(ClickHouseUnavailableException.class)
                                .ignoreExceptions(ClickHouseRequestRejectedException.class)
                                .build());
        writer = mock(JdbcClient.class);
        reader = mock(JdbcClient.class);
        batch = mock(JdbcClient.class);
        operations =
                new ClickHouseOperationsImpl(
                        gate, registry, ObservationRegistry.NOOP, writer, reader, batch);
    }

    private static UncategorizedSQLException serverError(int code) {
        return new UncategorizedSQLException("sql", "sql", new SQLException("Code", "22000", code));
    }

    @Test
    void read_usesTheReaderClient() {
        AtomicReference<JdbcClient> seen = new AtomicReference<>();

        operations.read("test.read", client -> seen.getAndSet(client));

        assertThat(seen.get()).isSameAs(reader);
    }

    @Test
    void readBatch_usesTheBatchClient() {
        AtomicReference<JdbcClient> seen = new AtomicReference<>();

        operations.readBatch("test.batch", client -> seen.getAndSet(client));

        assertThat(seen.get()).isSameAs(batch);
    }

    @Test
    void write_usesTheWriterClient() {
        AtomicReference<JdbcClient> seen = new AtomicReference<>();

        operations.write("test.write", seen::set);

        assertThat(seen.get()).isSameAs(writer);
    }

    @Test
    void read_schemaNotReady_failsAsNotReadyWithoutTouchingTheBreaker() {
        when(gate.isReady()).thenReturn(false);

        assertThatThrownBy(() -> operations.read("test.read", client -> "never"))
                .isInstanceOfSatisfying(
                        ClickHouseUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_READY));
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void read_serverFailure_isUnavailableAndCountsAgainstTheBreaker() {
        assertThatThrownBy(
                        () ->
                                operations.read(
                                        "test.read",
                                        client -> {
                                            throw serverError(241);
                                        }))
                .isInstanceOfSatisfying(
                        ClickHouseUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.SERVER));
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void write_rejectedRequest_isRejectedAndNeverCountedByTheBreaker() {
        assertThatThrownBy(
                        () ->
                                operations.write(
                                        "test.write",
                                        client -> {
                                            throw serverError(691);
                                        }))
                .isInstanceOf(ClickHouseRequestRejectedException.class);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void read_enoughAvailabilityFailures_opensTheBreakerAndFurtherCallsAreRefused() {
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(
                            () ->
                                    operations.read(
                                            "test.read",
                                            client -> {
                                                throw serverError(159);
                                            }))
                    .isInstanceOf(ClickHouseUnavailableException.class);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> operations.read("test.read", client -> "never"))
                .isInstanceOfSatisfying(
                        ClickHouseUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CIRCUIT_OPEN));
    }

    @Test
    void read_callerBug_propagatesUntranslatedAndDoesNotCountAsFailure() {
        assertThatThrownBy(
                        () ->
                                operations.read(
                                        "test.read",
                                        client -> {
                                            throw new IllegalArgumentException("bug");
                                        }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void isReady_followsTheGate() {
        assertThat(operations.isReady()).isTrue();

        when(gate.isReady()).thenReturn(false);

        assertThat(operations.isReady()).isFalse();
    }

    @Test
    void disabledOperations_everyCallFailsAsNotReady() {
        DisabledClickHouseOperations disabled = new DisabledClickHouseOperations();

        assertThat(disabled.isReady()).isFalse();
        assertThatThrownBy(() -> disabled.read("x", client -> "never"))
                .isInstanceOfSatisfying(
                        ClickHouseUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_READY));
        assertThatThrownBy(() -> disabled.write("x", client -> {}))
                .isInstanceOf(ClickHouseUnavailableException.class);
        assertThatThrownBy(() -> disabled.readBatch("x", client -> "never"))
                .isInstanceOf(ClickHouseUnavailableException.class);
    }
}
