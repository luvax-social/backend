package com.app.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class W3cTraceContextTest {

    private static final String VALID_TRACEPARENT =
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test
    void fromCarrier_validHeader_isParsed() {
        Map<String, String> carrier =
                Map.of("traceparent", VALID_TRACEPARENT, "tracestate", "congo=t61");

        Optional<W3cTraceContext> result = W3cTraceContext.fromCarrier(carrier);

        assertThat(result).isPresent();
        assertThat(result.get().traceParent()).isEqualTo(VALID_TRACEPARENT);
        assertThat(result.get().traceState()).isEqualTo("congo=t61");
    }

    @Test
    void fromCarrier_invalidTraceparent_isEmpty() {
        Map<String, String> carrier = Map.of("traceparent", "not-a-traceparent");

        assertThat(W3cTraceContext.fromCarrier(carrier)).isEmpty();
    }

    @Test
    void fromCarrier_blankTraceparent_isEmpty() {
        Map<String, String> carrier = Map.of("traceparent", "");

        assertThat(W3cTraceContext.fromCarrier(carrier)).isEmpty();
    }

    @Test
    void fromCarrier_missingTraceparent_isEmpty() {
        assertThat(W3cTraceContext.fromCarrier(Map.of())).isEmpty();
    }

    @Test
    void fromCarrier_overLongTracestate_dropped() {
        Map<String, String> carrier =
                Map.of("traceparent", VALID_TRACEPARENT, "tracestate", "k=" + "v".repeat(600));

        Optional<W3cTraceContext> result = W3cTraceContext.fromCarrier(carrier);

        assertThat(result).isPresent();
        assertThat(result.get().traceState()).isNull();
    }

    @Test
    void constructor_malformedTraceparent_rejected() {
        assertThatThrownBy(() -> new W3cTraceContext("not-a-traceparent", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_traceStateOverLimit_rejected() {
        assertThatThrownBy(() -> new W3cTraceContext(VALID_TRACEPARENT, "v".repeat(513)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
