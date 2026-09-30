package com.app.common.analytics;

/**
 * Base of the two failures every ClickHouse caller handles: {@link ClickHouseUnavailableException}
 * and {@link ClickHouseRequestRejectedException}. It is the one type a caller that only wants to
 * degrade, such as the For You top-up, needs to catch.
 */
public abstract class ClickHouseException extends RuntimeException {

    protected ClickHouseException(String message, Throwable cause) {
        super(message, cause);
    }
}
