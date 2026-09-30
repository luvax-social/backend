-- One value per half-hour bucket, metric and dimension, written from the rows StatsCollectionJob
-- computes in PostgreSQL. A re-collected bucket carries a later computed_at and replaces the
-- earlier value under FINAL, the ClickHouse form of the old ON CONFLICT DO UPDATE. There is no
-- daily grain: daily figures are computed at query time (last bucket of the day for gauges, sum
-- for flows).
CREATE TABLE IF NOT EXISTS platform_stats
(
    bucket_start DateTime('UTC'),
    metric_key   LowCardinality(String),
    dimension    LowCardinality(String),
    value        Int64,
    computed_at  DateTime64(6, 'UTC')
)
ENGINE = ReplacingMergeTree(computed_at)
PARTITION BY toYear(bucket_start)
ORDER BY (metric_key, bucket_start, dimension)
SETTINGS fsync_after_insert = 1, fsync_part_directory = 1;
