package com.app.common.observability;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.util.Assert;

/** A W3C trace context as carried in the traceparent and tracestate headers. */
public record W3cTraceContext(String traceParent, String traceState) {

    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";

    private static final Pattern TRACEPARENT_FORMAT =
            Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");
    private static final int MAX_TRACESTATE_LENGTH = 512;

    public W3cTraceContext {
        Assert.isTrue(
                traceParent != null && TRACEPARENT_FORMAT.matcher(traceParent).matches(),
                "traceParent must be a W3C traceparent");
        Assert.isTrue(
                traceState == null || traceState.length() <= MAX_TRACESTATE_LENGTH,
                "traceState must not exceed 512 characters");
    }

    /**
     * Reads a W3C trace context out of a header carrier, or empty when no valid traceparent is
     * present.
     *
     * @param carrier the header map to read from
     * @return the parsed context, or empty when the carrier has no valid traceparent
     */
    public static Optional<W3cTraceContext> fromCarrier(Map<String, String> carrier) {
        String traceParent = carrier.get(TRACEPARENT);
        if (traceParent == null || !TRACEPARENT_FORMAT.matcher(traceParent).matches()) {
            return Optional.empty();
        }
        String traceState = carrier.get(TRACESTATE);
        if (traceState != null
                && (traceState.isBlank() || traceState.length() > MAX_TRACESTATE_LENGTH)) {
            traceState = null;
        }
        return Optional.of(new W3cTraceContext(traceParent, traceState));
    }
}
