-- The behavioural event store. It replaced the partitioned PostgreSQL table of the same name.
-- Identity is the event id; every other sorting column is fixed for a given id, so a redelivered
-- copy lands on the same key and a merge folds it away. Reads that must be exact use FINAL.
CREATE TABLE IF NOT EXISTS user_events
(
    id             UUID DEFAULT generateUUIDv4(),
    user_id        UUID,
    event_type     Enum8('post_view' = 1, 'post_like' = 2, 'post_unlike' = 3, 'post_save' = 4,
                         'post_unsave' = 5, 'post_share' = 6, 'post_comment' = 7, 'story_view' = 8,
                         'story_reply' = 9, 'profile_view' = 10, 'profile_follow' = 11,
                         'profile_unfollow' = 12, 'search' = 13, 'hashtag_click' = 14,
                         'comment_like' = 15, 'comment_reply' = 16, 'message_send' = 17,
                         'session_start' = 18, 'session_end' = 19, 'app_open' = 20),
    entity_type    LowCardinality(Nullable(String)),
    entity_id      Nullable(UUID),
    metadata       String DEFAULT '',
    -- The Gorse feedback type and value this event was sent with, null when it was never sent.
    -- Stored rather than re-derived so a Gorse rebuild reproduces exactly what the live
    -- pipeline accumulated, dwell seconds and the half-weight comment like included.
    feedback_type  LowCardinality(Nullable(String)),
    feedback_value Nullable(Float64),
    created_at     DateTime64(6, 'UTC'),
    ingested_at    DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplacingMergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (user_id, event_type, created_at, id)
TTL toDateTime(created_at) + INTERVAL 12 MONTH
SETTINGS ttl_only_drop_parts = 1, fsync_after_insert = 1, fsync_part_directory = 1;
