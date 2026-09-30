-- Replica of PostgreSQL admin_actions. row_version comes from PostgreSQL, where a trigger bumps
-- it on every real update, so FINAL always returns the latest state of a row whatever order the
-- replication events arrived in. Only immutable columns are in the sorting key: admin_id,
-- target_user_id and report_id can be set to null by a cascade.
CREATE TABLE IF NOT EXISTS admin_actions
(
    id                 UUID,
    admin_id           Nullable(UUID),
    action_type        LowCardinality(String),
    target_user_id     Nullable(UUID),
    target_entity_type LowCardinality(Nullable(String)),
    target_entity_id   Nullable(UUID),
    report_id          Nullable(UUID),
    reason             Nullable(String),
    metadata           String DEFAULT '',
    created_at         DateTime64(6, 'UTC'),
    row_version        UInt64,
    replicated_at      DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplacingMergeTree(row_version)
PARTITION BY toYear(created_at)
ORDER BY (created_at, id)
SETTINGS fsync_after_insert = 1, fsync_part_directory = 1;
