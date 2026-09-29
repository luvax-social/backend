package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.app.modules.admin.enums.PlatformMetric;

@Repository
public class PlatformStatsRepositoryImpl implements PlatformStatsRepository {

    private static final String SELECT_TEMPLATE =
            "SELECT source.dimension AS dimension, source.value AS value FROM (%s) AS source";

    private final JdbcTemplate jdbcTemplate;

    public PlatformStatsRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    // The selected statement is from the closed PlatformMetric enum; bucket values are bound.
    @SuppressWarnings("java:S2077")
    public List<DimensionValue> compute(
            PlatformMetric metric, OffsetDateTime bucketStart, OffsetDateTime bucketEnd) {
        String sql = String.format(SELECT_TEMPLATE, metric.selectSql());
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DimensionValue(rs.getString("dimension"), rs.getLong("value")),
                bindsFor(metric, bucketStart, bucketEnd).toArray());
    }

    // A gauge is bounded only by the end of the bucket, so every placeholder in its statement takes
    // the bucket end. A flow is bounded by both edges and always writes them in that order.
    private static List<Object> bindsFor(
            PlatformMetric metric, OffsetDateTime bucketStart, OffsetDateTime bucketEnd) {
        int placeholders = countPlaceholders(metric.selectSql());
        if (metric.kind() == PlatformMetric.Kind.FLOW) {
            List<Object> binds = new ArrayList<>();
            for (int i = 0; i < placeholders; i += 2) {
                binds.add(bucketStart);
                binds.add(bucketEnd);
            }
            return binds;
        }
        return new ArrayList<>(Collections.nCopies(placeholders, bucketEnd));
    }

    private static int countPlaceholders(String sql) {
        int count = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') {
                count++;
            }
        }
        return count;
    }
}
