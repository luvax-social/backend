package com.app.common.outbox.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxMetricsSamplerTest {

    private NamedParameterJdbcTemplate jdbcTemplate;
    private SimpleMeterRegistry registry;
    private OutboxMetricsSampler sampler;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        registry = new SimpleMeterRegistry();
        sampler = new OutboxMetricsSampler(jdbcTemplate, registry);
    }

    @Test
    void sample_queryReturnsRow_gaugesReflectIt() {
        when(jdbcTemplate.queryForMap(anyString(), anyMap()))
                .thenReturn(
                        Map.of(
                                "pending", 3L,
                                "processing", 1L,
                                "published", 100L,
                                "dead", 2L,
                                "oldest_pending_age_seconds", 45L));

        sampler.sample();

        assertThat(gaugeValue("status", "PENDING")).isEqualTo(3.0);
        assertThat(gaugeValue("status", "PROCESSING")).isEqualTo(1.0);
        assertThat(gaugeValue("status", "PUBLISHED")).isEqualTo(100.0);
        assertThat(gaugeValue("status", "DEAD")).isEqualTo(2.0);
        assertThat(registry.get("luvax.outbox.oldest.pending.age").gauge().value()).isEqualTo(45.0);
    }

    @Test
    void sample_queryFails_previousValuesRetained() {
        when(jdbcTemplate.queryForMap(anyString(), anyMap()))
                .thenReturn(
                        Map.of(
                                "pending", 5L,
                                "processing", 0L,
                                "published", 10L,
                                "dead", 0L,
                                "oldest_pending_age_seconds", 12L));
        sampler.sample();

        when(jdbcTemplate.queryForMap(anyString(), anyMap()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        sampler.sample();

        assertThat(gaugeValue("status", "PENDING")).isEqualTo(5.0);
        assertThat(registry.get("luvax.outbox.oldest.pending.age").gauge().value()).isEqualTo(12.0);
    }

    private double gaugeValue(String tagKey, String tagValue) {
        return registry.get("luvax.outbox.events").tag(tagKey, tagValue).gauge().value();
    }
}
