-- Adds the notification types used when moderation removes a comment, a story or a message.
--
-- Post removal was the only enforcement with an in-product notification. For the other three the
-- owner still holds a valid session and was told nothing in-product, so the content simply
-- vanished. Ban and suspension deliberately get no type: a banned or suspended account cannot
-- load an authenticated surface, so the row would be written and never read, and mail stays
-- their only channel.
--
-- This file contains only the enum extension. PostgreSQL does not make a new enum value usable
-- inside the same transaction that added it, so the notification_type_configs rows live in V113.

ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'comment_removed';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'story_removed';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'message_removed';
