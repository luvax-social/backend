package com.app.modules.admin.repository;

import java.time.OffsetDateTime;
import java.util.List;

import com.app.modules.admin.enums.PlatformMetric;

/**
 * Computes platform statistics from the operational tables.
 *
 * <p>Every counting statement runs entirely inside the database. The counts are over the whole of
 * {@code users}, {@code posts} and {@code comments}, so bringing rows into the application to count
 * them there would move millions of rows across the wire to produce one number.
 *
 * <p>This repository only computes. Where the results are kept is the analytics store's concern,
 * and the collection service hands them to it through the outbox.
 */
public interface PlatformStatsRepository {

    /**
     * Computes one metric for one bucket.
     *
     * @param metric which metric to compute
     * @param bucketStart inclusive start of the bucket
     * @param bucketEnd exclusive end of the bucket
     * @return one entry per dimension the metric produces; a dimension nobody holds has no entry
     */
    List<DimensionValue> compute(
            PlatformMetric metric, OffsetDateTime bucketStart, OffsetDateTime bucketEnd);

    /**
     * One computed value.
     *
     * @param dimension breakdown key, empty when the metric has no breakdown
     * @param value the computed number
     */
    record DimensionValue(String dimension, long value) {}
}
