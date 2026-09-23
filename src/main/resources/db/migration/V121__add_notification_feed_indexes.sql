-- Indexes for the activity feed, its filters, the unseen badge, aggregation and retraction.
--
-- Every statement is CREATE INDEX CONCURRENTLY, so this migration runs non-transactionally via its
-- .sql.conf sidecar. The indexes these replace are dropped by V122, after these are live, so the
-- feed query is never without an index (the V107 swap order).

-- The "all" feed, every keyset page, the page-0 head, and the bounded unseen badge:
--   WHERE recipient_id = ? AND deleted_at IS NULL AND (activity_at, id) < (?, ?)
--   ORDER BY activity_at DESC, id DESC LIMIT ?
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notifications_feed
    ON notifications (recipient_id, activity_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- The "unread" filter: the same shape restricted to unread rows.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notifications_feed_unread
    ON notifications (recipient_id, activity_at DESC, id DESC)
    WHERE read_at IS NULL AND deleted_at IS NULL;

-- The comments, mentions, follows and system filters: the same shape plus category = ?.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notifications_feed_category
    ON notifications (recipient_id, category, activity_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- The "verified" filter: rows whose newest actor holds a verified badge.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notifications_feed_verified
    ON notifications (recipient_id, activity_at DESC, id DESC)
    WHERE actor_verified AND deleted_at IS NULL;

-- The aggregation upsert target: at most one open group per recipient and key. Concurrent first
-- actors on the same target serialise on this index, so they join one group instead of opening
-- two.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_notifications_open_group
    ON notifications (recipient_id, aggregation_key)
    WHERE is_group_open AND deleted_at IS NULL;

-- Retraction on unlike or unfollow, which must reach closed groups as well as the open one.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notifications_aggregation_key
    ON notifications (recipient_id, aggregation_key)
    WHERE aggregation_key IS NOT NULL AND deleted_at IS NULL;

-- The displayed actors of each row, newest first (LATERAL ... ORDER BY acted_at DESC LIMIT 3).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notification_actors_recent
    ON notification_actors (notification_id, acted_at DESC);

-- The actor_id foreign key (user hard-delete cascade), block cleanup and the verified resync.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notification_actors_actor
    ON notification_actors (actor_id);
