-- Drops notifications.is_read. read_at is the single read-state column: a notification is read
-- exactly when read_at is set.
--
-- The two agreed on every row before this release, and every value is in
-- notifications_pre_overhaul_archive (V116). Its only index went in V122. A separate migration so
-- a failure here is isolated from the data migrations before it.

ALTER TABLE notifications DROP COLUMN is_read;
