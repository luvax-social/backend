package com.app.modules.admin.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;

/**
 * The ClickHouse store of platform statistics.
 *
 * <p>Holds one value per half-hour bucket, metric and dimension. There is no daily grain: a daily
 * figure is computed here at read time, by summing a flow's buckets and taking a gauge's last
 * bucket of the day.
 *
 * <p>Every read uses {@code FINAL}, so a bucket that was collected twice appears once, with the
 * values of the collection that ran last. The newest-bucket lookup is the one exception, because
 * duplicates cannot change a maximum.
 */
public interface PlatformStatsAnalyticsRepository {

    /**
     * Writes every row of one bucket.
     *
     * <p>Acknowledged only after ClickHouse has written the part, so a consumer that acknowledges
     * its message afterwards never loses a bucket that can never be collected again. Writing the
     * same bucket twice is harmless, and the rows with the later {@code computedAt} win when
     * ClickHouse merges.
     *
     * @throws com.app.common.analytics.ClickHouseUnavailableException when the store cannot take
     *     the write right now
     */
    void insertBucket(
            Instant bucketStart, Instant computedAt, List<PlatformStatsCollectedEvent.Row> rows);

    /** Returns the start of the newest bucket that holds any row, or empty when there is none. */
    Optional<OffsetDateTime> findNewestBucket();

    /** Reads every metric row of one bucket. */
    List<StatRow> findBucket(OffsetDateTime bucketStart);

    /**
     * Reads one metric's half-hour series, oldest first.
     *
     * @param from inclusive lower bound
     * @param to exclusive upper bound
     */
    List<SeriesPoint> findHalfHourSeries(String metricKey, OffsetDateTime from, OffsetDateTime to);

    /**
     * Reads a flow metric's daily series: the sum of each UTC day's buckets.
     *
     * @param firstDay inclusive UTC midnight of the first day
     * @param endDay exclusive UTC midnight after the last day
     */
    List<SeriesPoint> findDailyFlowSeries(
            String metricKey, OffsetDateTime firstDay, OffsetDateTime endDay);

    /**
     * Reads a gauge metric's daily series: the state at the end of each UTC day, which is its last
     * bucket. A dimension absent from that bucket has no point, and readers treat it as zero.
     *
     * @param firstDay inclusive UTC midnight of the first day
     * @param endDay exclusive UTC midnight after the last day
     */
    List<SeriesPoint> findDailyGaugeSeries(
            String metricKey, OffsetDateTime firstDay, OffsetDateTime endDay);

    /**
     * One stored value of a bucket.
     *
     * @param bucketStart bucket the value belongs to
     * @param metricKey which metric
     * @param dimension breakdown key, empty when the metric has no breakdown
     * @param value the recorded number
     * @param computedAt when the collection that produced it ran
     */
    record StatRow(
            OffsetDateTime bucketStart,
            String metricKey,
            String dimension,
            long value,
            OffsetDateTime computedAt) {}

    /**
     * One point of a series.
     *
     * @param bucketStart start of the bucket, or the UTC midnight of the day for a daily series
     * @param dimension breakdown key, empty when the metric has no breakdown
     * @param value the number
     */
    record SeriesPoint(OffsetDateTime bucketStart, String dimension, long value) {}
}
