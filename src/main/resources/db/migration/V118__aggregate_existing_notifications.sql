-- Collapses existing aggregatable notifications into groups, so history matches the live model.
--
-- Aggregatable types are like_post, like_comment, story_view and follow; V116 set their
-- aggregation_key. Live grouping opens a group for 24 hours from its first actor. History has no
-- group boundaries to recover, so this backfill buckets by UTC calendar day instead: one group per
-- (recipient, aggregation key, UTC day).
--
-- Per bucket, the newest row is kept as the group. Every other row's actor membership moves onto
-- it and the other rows are deleted. Every deleted row is in notifications_pre_overhaul_archive
-- (V116), which makes the collapse reversible.
--
-- The kept row's created_at is not rewritten (it stays database-set, per the timestamp policy);
-- its activity_at already equals its created_at, which is the newest in the bucket. The group is
-- read only if every member was read, at the latest member's read time.

CREATE TEMPORARY TABLE notification_group_map ON COMMIT DROP AS
SELECT id,
       first_value(id) OVER bucket_newest_first            AS keeper_id,
       min(created_at) OVER bucket                         AS started_at,
       bool_and(read_at IS NOT NULL) OVER bucket           AS all_read,
       max(read_at) OVER bucket                            AS last_read_at
  FROM notifications
 WHERE aggregation_key IS NOT NULL
WINDOW bucket AS (PARTITION BY recipient_id, aggregation_key,
                               date_trunc('day', created_at AT TIME ZONE 'UTC')),
       bucket_newest_first AS (bucket ORDER BY created_at DESC, id DESC);

-- The counter trigger (V117) increments each keeper once per membership it gains.
INSERT INTO notification_actors (notification_id, actor_id, acted_at)
SELECT m.keeper_id, na.actor_id, na.acted_at
  FROM notification_group_map m
  JOIN notification_actors na ON na.notification_id = m.id
 WHERE m.id <> m.keeper_id
ON CONFLICT (notification_id, actor_id) DO NOTHING;

DELETE FROM notifications n
 USING notification_group_map m
 WHERE n.id = m.id AND m.id <> m.keeper_id;

UPDATE notifications n
   SET group_started_at = m.started_at,
       read_at          = CASE WHEN m.all_read THEN m.last_read_at END
  FROM notification_group_map m
 WHERE n.id = m.id AND m.id = m.keeper_id;

-- The newest group per key stays open if it started less than 24 hours ago, matching the live
-- window; everything else is closed, so the next actor on an old target starts a new group.
UPDATE notifications n
   SET is_group_open = TRUE
  FROM (SELECT DISTINCT ON (recipient_id, aggregation_key) id, group_started_at
          FROM notifications
         WHERE aggregation_key IS NOT NULL AND deleted_at IS NULL
         ORDER BY recipient_id, aggregation_key, activity_at DESC, id DESC) newest
 WHERE n.id = newest.id
   AND newest.group_started_at > NOW() - INTERVAL '24 hours';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM notifications n
         WHERE n.actor_count <> (SELECT count(*) FROM notification_actors na
                                  WHERE na.notification_id = n.id)) THEN
        RAISE EXCEPTION 'notifications.actor_count drifted from notification_actors';
    END IF;
END;
$$;
