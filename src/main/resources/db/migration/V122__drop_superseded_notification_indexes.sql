-- Drops the notification indexes V121 replaced, now that their replacements are live.
--
-- idx_notifications_recipient (V15) had recorded no scans since V39 superseded it.
-- idx_notifications_recipient_created_id (V39) ordered by created_at, which is no longer the feed
-- sort key; idx_notifications_feed orders by activity_at. idx_notifications_unread (V15) keyed on
-- is_read, which V123 drops; idx_notifications_feed_unread replaces it.
--
-- DROP INDEX CONCURRENTLY does not take an ACCESS EXCLUSIVE lock on notifications, and cannot run
-- inside a transaction block, hence the .sql.conf sidecar.

DROP INDEX CONCURRENTLY IF EXISTS idx_notifications_recipient;
DROP INDEX CONCURRENTLY IF EXISTS idx_notifications_recipient_created_id;
DROP INDEX CONCURRENTLY IF EXISTS idx_notifications_unread;
