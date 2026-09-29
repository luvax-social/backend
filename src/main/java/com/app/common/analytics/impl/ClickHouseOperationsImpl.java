package com.app.common.analytics.impl;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.app.common.analytics.ClickHouseErrorTranslator;
import com.app.common.analytics.ClickHouseOperations;
import com.app.common.analytics.ClickHouseUnavailableException;
import com.app.common.analytics.ClickHouseUnavailableException.Reason;
import com.app.common.analytics.migration.AnalyticsSchemaGate;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

@Component
@ConditionalOnProperty(name = "app.analytics.enabled", havingValue = "true")
public class ClickHouseOperationsImpl implements ClickHouseOperations {

    /** Name of the circuit breaker instance in the resilience4j configuration. */
    public static final String CIRCUIT_BREAKER_NAME = "clickhouse";

    private final AnalyticsSchemaGate schemaGate;
    private final CircuitBreaker circuitBreaker;
    private final ObservationRegistry observationRegistry;
    private final JdbcClient writer;
    private final JdbcClient reader;
    private final JdbcClient batch;

    public ClickHouseOperationsImpl(
            AnalyticsSchemaGate schemaGate,
            CircuitBreakerRegistry circuitBreakerRegistry,
            ObservationRegistry observationRegistry,
            @Qualifier("clickHouseWriterJdbcClient") JdbcClient writer,
            @Qualifier("clickHouseReaderJdbcClient") JdbcClient reader,
            @Qualifier("clickHouseBatchJdbcClient") JdbcClient batch) {
        this.schemaGate = schemaGate;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
        this.observationRegistry = observationRegistry;
        this.writer = writer;
        this.reader = reader;
        this.batch = batch;
    }

    @Override
    public <T> T read(String operation, Function<JdbcClient, T> query) {
        return execute(operation, reader, query);
    }

    @Override
    public <T> T readBatch(String operation, Function<JdbcClient, T> query) {
        return execute(operation, batch, query);
    }

    @Override
    public void write(String operation, Consumer<JdbcClient> insert) {
        execute(
                operation,
                writer,
                client -> {
                    insert.accept(client);
                    return null;
                });
    }

    @Override
    public boolean isReady() {
        return schemaGate.isReady();
    }

    private <T> T execute(String operation, JdbcClient client, Function<JdbcClient, T> call) {
        // Refused before the breaker, so a slow start can never open it.
        if (!schemaGate.isReady()) {
            throw new ClickHouseUnavailableException(
                    Reason.NOT_READY, "analytics schema not ready");
        }
        tagOperation(operation);
        Supplier<T> guarded = () -> invoke(client, call);
        try {
            return circuitBreaker.executeSupplier(guarded);
        } catch (CallNotPermittedException e) {
            throw new ClickHouseUnavailableException(Reason.CIRCUIT_OPEN, "circuit open", e);
        }
    }

    private static <T> T invoke(JdbcClient client, Function<JdbcClient, T> call) {
        try {
            return call.apply(client);
        } catch (DataAccessException e) {
            // Translated inside the breaker, which records only availability failures.
            throw ClickHouseErrorTranslator.translate(e);
        }
    }

    private void tagOperation(String operation) {
        Observation current = observationRegistry.getCurrentObservation();
        if (current != null) {
            current.highCardinalityKeyValue("clickhouse.operation", operation);
        }
    }
}
