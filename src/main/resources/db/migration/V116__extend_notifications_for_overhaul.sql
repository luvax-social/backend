-- Extends notifications for the activity feed: aggregation, soft delete, a movable sort key, a
-- filter category, the verified-actor filter, and the audit row behind every moderation notice.
--
-- Archive first. This migration nulls the staff actor on support notices and normalises the
-- follow row shape, V118 collapses aggregatable rows into groups and deletes the rest, V119
-- soft-deletes direct-message rows, and V123 drops is_read. Every original row and value is kept
-- in the archive, so each of those steps is reversible. The archive is kept for at least one
-- release and dropped by a later migration.
CREATE TABLE notifications_pre_overhaul_archive AS TABLE notifications;

COMMENT ON TABLE notifications_pre_overhaul_archive IS
    'Every notifications row as it stood before V116-V123. Read-only; drop in a later release.';

ALTER TABLE notifications
    ADD COLUMN activity_at      TIMESTAMPTZ,
    ADD COLUMN category         notification_category,
    ADD COLUMN aggregation_key  TEXT,
    ADD COLUMN is_group_open    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN group_started_at TIMESTAMPTZ,
    ADD COLUMN actor_count      INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN actor_verified   BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN admin_action_id  UUID,
    ADD COLUMN deleted_at       TIMESTAMPTZ;

-- A support answer comes from the platform, not from the staff member who wrote it. Naming them
-- sent the recipient to a moderator's profile and let a block against that moderator hide the
-- answer. Every other enforcement or support notice already carries a null actor.
UPDATE notifications SET actor_id = NULL
 WHERE type = 'support_ticket_update' AND actor_id IS NOT NULL;

-- Production writes follow rows with no entity; the seed wrote entity_type 'follow' and the
-- follower id. One shape for both.
UPDATE notifications
   SET entity_type = NULL, entity_id = NULL
 WHERE type IN ('follow', 'follow_request')
   AND (entity_type IS NOT NULL OR entity_id IS NOT NULL);

-- A post like targets the post itself; post_id is set on every content row so a client can open
-- the post without resolving the entity first.
UPDATE notifications SET post_id = entity_id
 WHERE type = 'like_post' AND post_id IS NULL AND entity_id IS NOT NULL;

UPDATE notifications n
   SET activity_at      = n.created_at,
       category         = (CASE n.type
                               WHEN 'like_post'       THEN 'like'
                               WHEN 'like_comment'    THEN 'like'
                               WHEN 'comment_post'    THEN 'comment'
                               WHEN 'reply_comment'   THEN 'comment'
                               WHEN 'mention_post'    THEN 'mention'
                               WHEN 'mention_comment' THEN 'mention'
                               WHEN 'follow'          THEN 'follow'
                               WHEN 'follow_request'  THEN 'follow'
                               WHEN 'story_view'      THEN 'story'
                               WHEN 'message'         THEN 'message'
                               ELSE 'system'
                           END)::notification_category,
       aggregation_key  = CASE n.type
                               WHEN 'like_post'    THEN 'like_post:' || n.entity_id
                               WHEN 'like_comment' THEN 'like_comment:' || n.entity_id
                               WHEN 'story_view'   THEN 'story_view:' || n.entity_id
                               WHEN 'follow'       THEN 'follow'
                           END,
       group_started_at = CASE WHEN n.type IN ('like_post', 'like_comment', 'story_view', 'follow')
                               THEN n.created_at END,
       actor_verified   = COALESCE(
                              (SELECT u.is_verified FROM users u WHERE u.id = n.actor_id), FALSE);

ALTER TABLE notifications
    ALTER COLUMN activity_at SET DEFAULT NOW(),
    ALTER COLUMN activity_at SET NOT NULL,
    ALTER COLUMN category    SET NOT NULL;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_actor_count_nonnegative CHECK (actor_count >= 0),
    ADD CONSTRAINT chk_notifications_open_group
        CHECK (NOT is_group_open OR (aggregation_key IS NOT NULL AND group_started_at IS NOT NULL));

-- admin_action_id: the audit row behind each moderation notice, which carries the reason, the
-- affected content and the appeal route. No foreign key, matching entity_id and post_id.

-- Comment, story and message removal already stored the audit id as the entity.
UPDATE notifications
   SET admin_action_id = entity_id
 WHERE type IN ('comment_removed', 'story_removed', 'message_removed')
   AND entity_type = 'admin_action';

-- A warning stores the user_warnings id, which names its audit row.
UPDATE notifications n
   SET admin_action_id = w.admin_action_id
  FROM user_warnings w
 WHERE n.type = 'warning' AND w.id = n.entity_id;

-- Warning rows written without the warning id (the seed wrote them that way) take the recipient's
-- warning closest in time. Production rows always carry the id and are matched above.
UPDATE notifications n
   SET admin_action_id = (
           SELECT w.admin_action_id
             FROM user_warnings w
            WHERE w.user_id = n.recipient_id
            ORDER BY abs(extract(epoch FROM w.created_at - n.created_at)), w.id
            LIMIT 1)
 WHERE n.type = 'warning' AND n.entity_id IS NULL AND n.admin_action_id IS NULL;

-- Post removal and restore stored the post id. The notice is written in the same transaction as
-- its audit row, so in production both carry the same created_at and the closest matching audit
-- row in time is exact; for rows written any other way it is the best available match. An audit
-- row naming the recipient is preferred; one with no target user (the seed wrote them that way)
-- is accepted only when none names the recipient.
UPDATE notifications n
   SET admin_action_id = (
           SELECT a.id
             FROM admin_actions a
            WHERE a.action_type = (CASE n.type WHEN 'post_removed' THEN 'remove_post'
                                               ELSE 'restore_post' END)::admin_action_type
              AND a.target_entity_id = n.entity_id
              AND (a.target_user_id = n.recipient_id OR a.target_user_id IS NULL)
            ORDER BY a.target_user_id IS NULL,
                     abs(extract(epoch FROM a.created_at - n.created_at)), a.id
            LIMIT 1)
 WHERE n.type IN ('post_removed', 'post_restored');

-- The reporter's notices stored the report id; the decision on that report names it.
UPDATE notifications n
   SET admin_action_id = (
           SELECT a.id
             FROM admin_actions a
            WHERE a.report_id = n.entity_id
              AND a.action_type = (CASE n.type WHEN 'report_post_removed' THEN 'remove_post'
                                               ELSE 'dismiss_report' END)::admin_action_type
            ORDER BY abs(extract(epoch FROM a.created_at - n.created_at)), a.id
            LIMIT 1)
 WHERE n.type IN ('report_post_removed', 'report_dismissed');
