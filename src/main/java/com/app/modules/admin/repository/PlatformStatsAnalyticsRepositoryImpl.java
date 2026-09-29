package com.app.modules.admin.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.app.common.analytics.ClickHouseOperations;
import com.app.modules.admin.messaging.PlatformStatsCollectedEvent;

@Repository
public class PlatformStatsAnalyticsRepositoryImpl implements PlatformStatsAnalyticsRepository {

    // wait_for_async_insert = 1 makes the call return only once the part is written, so the message
    // acknowledgement that follows can never precede a durable row. The busy timeout comes from the
    // writer's settings profile, so it can be tuned in ClickHouse without a deploy.
    private static final String INSERT_PREFIX =
            "INSERT INTO platform_stats (bucket_start, metric_key, dimension, value, computed_at)"
                    + " SETTINGS async_insert = 1, wait_for_async_insert = 1 VALUES ";

    // max() over an empty table is the epoch rather than null, so the HAVING clause is what makes
    // an empty table return no row at all. Duplicates cannot change a maximum, so no FINAL.
    private static final String NEWEST_SQL =
            "SELECT max(bucket_start) AS newest FROM platform_stats HAVING count() > 0";

    private static final String BUCKET_SQL =
            String.join(
                    " ",
                    "SELECT bucket_start, metric_key, dimension, value, computed_at",
                    "FROM platform_stats FINAL WHERE bucket_start = :bucket");

    private static final String HALF_HOUR_SQL =
            String.join(
                    " ",
                    "SELECT bucket_start, dimension, value FROM platform_stats FINAL",
                    "WHERE metric_key = :metric AND bucket_start >= :from AND bucket_start < :to",
                    "ORDER BY bucket_start, dimension");

    // toStartOfDay on a DateTime('UTC') column truncates in UTC, which keeps day boundaries pinned
    // to UTC whatever the zone of the server or the session is.
    private static final String DAILY_FLOW_SQL =
            String.join(
                    " ",
                    "SELECT toStartOfDay(bucket_start) AS day, dimension, sum(value) AS value",
                    "FROM platform_stats FINAL",
                    "WHERE metric_key = :metric AND bucket_start >= :firstDay",
                    "AND bucket_start < :endDay",
                    "GROUP BY day, dimension ORDER BY day, dimension");

    // The state at the end of a day is its last bucket. A dimension that bucket does not hold is
    // absent from the answer and reads as zero, rather than keeping an earlier bucket's value.
    private static final String DAILY_GAUGE_SQL =
            String.join(
                    " ",
                    "SELECT day, dimension, value FROM (",
                    "SELECT toStartOfDay(bucket_start) AS day, bucket_start, dimension, value,",
                    "max(bucket_start) OVER (PARTITION BY toStartOfDay(bucket_start))",
                    "AS last_bucket",
                    "FROM platform_stats FINAL",
                    "WHERE metric_key = :metric AND bucket_start >= :firstDay",
                    "AND bucket_start < :endDay)",
                    "WHERE bucket_start = last_bucket ORDER BY day, dimension");

    private final ClickHouseOperations clickHouse;

    public PlatformStatsAnalyticsRepositoryImpl(ClickHouseOperations clickHouse) {
        this.clickHouse = clickHouse;
    }

    @Override
    public void insertBucket(
            Instant bucketStart, Instant computedAt, List<PlatformStatsCollectedEvent.Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(INSERT_PREFIX);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("bucket", bucketStart.atOffset(ZoneOffset.UTC));
        params.put("computedAt", computedAt.atOffset(ZoneOffset.UTC));
        for (int i = 0; i < rows.size(); i++) {
            PlatformStatsCollectedEvent.Row row = rows.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(:bucket, :metric")
                    .append(i)
                    .append(", :dimension")
                    .append(i)
                    .append(", :value")
                    .append(i)
                    .append(", :computedAt)");
            params.put("metric" + i, row.metricKey());
            params.put("dimension" + i, row.dimension());
            params.put("value" + i, row.value());
        }
        clickHouse.write(
                "platform_stats.insert",
                client -> client.sql(sql.toString()).params(params).update());
    }

    @Override
    public Optional<OffsetDateTime> findNewestBucket() {
        return clickHouse.read(
                "platform_stats.newest",
                client ->
                        client.sql(NEWEST_SQL)
                                .query(
                                        (rows, rowNumber) ->
                                                rows.getObject("newest", OffsetDateTime.class)
                                                        .withOffsetSameInstant(ZoneOffset.UTC))
                                .optional());
    }

    @Override
    public List<StatRow> findBucket(OffsetDateTime bucketStart) {
        return clickHouse.read(
                "platform_stats.bucket",
                client ->
                        client.sql(BUCKET_SQL)
                                .param("bucket", bucketStart)
                                .query(PlatformStatsAnalyticsRepositoryImpl::toStatRow)
                                .list());
    }

    @Override
    public List<SeriesPoint> findHalfHourSeries(
            String metricKey, OffsetDateTime from, OffsetDateTime to) {
        return clickHouse.read(
                "platform_stats.half_hour_series",
                client ->
                        client.sql(HALF_HOUR_SQL)
                                .param("metric", metricKey)
                                .param("from", from)
                                .param("to", to)
                                .query((rows, rowNumber) -> toPoint(rows, "bucket_start"))
                                .list());
    }

    @Override
    public List<SeriesPoint> findDailyFlowSeries(
            String metricKey, OffsetDateTime firstDay, OffsetDateTime endDay) {
        return dailySeries(
                "platform_stats.daily_flow_series", DAILY_FLOW_SQL, metricKey, firstDay, endDay);
    }

    @Override
    public List<SeriesPoint> findDailyGaugeSeries(
            String metricKey, OffsetDateTime firstDay, OffsetDateTime endDay) {
        return dailySeries(
                "platform_stats.daily_gauge_series", DAILY_GAUGE_SQL, metricKey, firstDay, endDay);
    }

    private List<SeriesPoint> dailySeries(
            String operation,
            String sql,
            String metricKey,
            OffsetDateTime firstDay,
            OffsetDateTime endDay) {
        return clickHouse.read(
                operation,
                client ->
                        client.sql(sql)
                                .param("metric", metricKey)
                                .param("firstDay", firstDay)
                                .param("endDay", endDay)
                                .query((rows, rowNumber) -> toPoint(rows, "day"))
                                .list());
    }

    private static SeriesPoint toPoint(ResultSet rows, String timeColumn) throws SQLException {
        return new SeriesPoint(
                rows.getObject(timeColumn, OffsetDateTime.class)
                        .withOffsetSameInstant(ZoneOffset.UTC),
                rows.getString("dimension"),
                rows.getLong("value"));
    }

    private static StatRow toStatRow(ResultSet rows, int rowNumber) throws SQLException {
        return new StatRow(
                rows.getObject("bucket_start", OffsetDateTime.class)
                        .withOffsetSameInstant(ZoneOffset.UTC),
                rows.getString("metric_key"),
                rows.getString("dimension"),
                rows.getLong("value"),
                rows.getObject("computed_at", OffsetDateTime.class)
                        .withOffsetSameInstant(ZoneOffset.UTC));
    }
}
