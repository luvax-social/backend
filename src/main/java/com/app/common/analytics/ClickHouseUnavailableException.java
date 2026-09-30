package com.app.common.analytics;

/**
 * ClickHouse cannot serve the call right now: the schema is not ready, the breaker is open, or the
 * server failed, timed out or refused it for a reason waiting can fix. Ingestion pauses on this
 * rather than dead-lettering, and reads fall back or answer 503.
 */
public class ClickHouseUnavailableException extends ClickHouseException {

    /** Why the call was not served, reported by the recorder's drop counter and the logs. */
    public enum Reason {
        NOT_READY,
        CIRCUIT_OPEN,
        SERVER
    }

    private final Reason reason;

    public ClickHouseUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ClickHouseUnavailableException(Reason reason, String message) {
        this(reason, message, null);
    }

    public Reason reason() {
        return reason;
    }
}
