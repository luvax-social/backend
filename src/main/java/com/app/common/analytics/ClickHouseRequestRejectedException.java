package com.app.common.analytics;

/**
 * ClickHouse refused the request itself: bad SQL, a value the column cannot hold, an exceeded
 * result cap. Retrying or pausing cannot help, so it never counts against the circuit breaker, and
 * a consumer dead-letters the message at once.
 */
public class ClickHouseRequestRejectedException extends ClickHouseException {

    private final int errorCode;

    public ClickHouseRequestRejectedException(String message, int errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** The ClickHouse server error code, for logs. */
    public int errorCode() {
        return errorCode;
    }
}
