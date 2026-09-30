package com.app.common.analytics;

import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The only way application code reaches ClickHouse.
 *
 * <p>Every call passes the schema gate and the {@code clickhouse} circuit breaker, and every
 * failure leaves as {@link ClickHouseUnavailableException} or {@link
 * ClickHouseRequestRejectedException}, so a caller never sees a driver or Spring JDBC type.
 */
public interface ClickHouseOperations {

    /**
     * Runs a short read on the request path with the reader user and the reader pool.
     *
     * @param operation a stable name for the call, tagged onto the current observation
     * @param query the read, given a client bound to the reader pool
     * @return whatever the query returns
     */
    <T> T read(String operation, Function<JdbcClient, T> query);

    /**
     * Runs a long read for a job, such as the affinity recompute or the Gorse rebuild, with the
     * reader user and the batch pool.
     *
     * @param operation a stable name for the call, tagged onto the current observation
     * @param query the read, given a client bound to the batch pool
     * @return whatever the query returns
     */
    <T> T readBatch(String operation, Function<JdbcClient, T> query);

    /**
     * Runs an insert with the writer user. The update count of an asynchronous insert is not
     * reliable, so the callback returns nothing.
     *
     * @param operation a stable name for the call, tagged onto the current observation
     * @param insert the insert, given a client bound to the writer pool
     */
    void write(String operation, Consumer<JdbcClient> insert);

    /**
     * Reports whether every ClickHouse migration has applied in this process.
     *
     * @return true once the schema gate has finished
     */
    boolean isReady();
}
