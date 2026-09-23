-- Adds the category a notification is filtered by in the activity feed.
--
-- The feed offers filters (comments, mentions, follows, system) that each span several
-- notification_type values, so a notification carries its category as its own column rather than
-- the filter enumerating types. The column is written by the application from
-- NotificationType.category() and backfilled by V116, not derived by a generated column: adding a
-- notification_type value later must not require rewriting a generated expression, and the enum
-- remains the constraint layer per the enum-versus-config rule.
--
-- The type is created in its own migration so it is committed before any later migration uses it.

CREATE TYPE notification_category AS ENUM
    ('like', 'comment', 'mention', 'follow', 'story', 'message', 'system');
