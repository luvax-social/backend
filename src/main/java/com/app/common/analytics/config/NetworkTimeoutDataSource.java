package com.app.common.analytics.config;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * A pooled ClickHouse {@code DataSource} that puts the socket timeout on every connection it hands
 * out.
 *
 * <p>HikariCP sets a connection's network timeout while validating it and then restores what the
 * driver reported before, which for the V2 driver is zero, meaning no timeout. That silently
 * replaces the {@code socket_timeout} the connection was opened with, so a hung server would block
 * a caller forever instead of failing it after the bound. Setting the timeout again on each
 * checkout makes the bound hold whatever Hikari did last.
 */
final class NetworkTimeoutDataSource extends DelegatingDataSource implements AutoCloseable {

    private final HikariDataSource pool;
    private final int timeoutMillis;

    NetworkTimeoutDataSource(HikariDataSource pool, int timeoutMillis) {
        super(pool);
        this.pool = pool;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withTimeout(pool.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withTimeout(pool.getConnection(username, password));
    }

    private Connection withTimeout(Connection connection) throws SQLException {
        try {
            // The driver ignores the executor: it stores the value as the request timeout.
            connection.setNetworkTimeout(Runnable::run, timeoutMillis);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    @Override
    public void close() {
        pool.close();
    }
}
