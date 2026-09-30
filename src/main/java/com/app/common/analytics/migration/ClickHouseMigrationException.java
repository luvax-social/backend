package com.app.common.analytics.migration;

/**
 * A ClickHouse schema migration cannot proceed: a checksum changed, a version arrived out of order,
 * a script is malformed, or a statement failed. Nothing in the history table records a failed
 * script, so the next run simply starts it again.
 */
public class ClickHouseMigrationException extends RuntimeException {

    public ClickHouseMigrationException(String message) {
        super(message);
    }

    public ClickHouseMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
