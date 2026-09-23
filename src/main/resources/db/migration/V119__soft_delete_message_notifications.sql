-- Removes direct messages from the activity feed.
--
-- A direct message already appears in Messages with its own unread state, so a second copy in
-- the activity feed was noise. The producer is retired in the same release. Existing rows are
-- soft-deleted rather than hard-deleted, and every one of them is also in
-- notifications_pre_overhaul_archive (V116).
--
-- The notification_type value 'message' and user_settings.notify_messages both stay: the type
-- keeps these rows readable, and the setting is reserved for a future push channel.

UPDATE notifications
   SET deleted_at = NOW()
 WHERE type = 'message' AND deleted_at IS NULL;
