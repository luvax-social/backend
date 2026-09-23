-- Seeds the seen watermark so the unseen badge does not light up with a user's whole history on
-- deploy.
--
-- Until this release, opening the notifications screen marked every notification read, so the
-- newest read notification is the best available record of a user's last visit. It becomes both
-- the seen and the previous watermark: the first open after deploy shows as "new" exactly what
-- arrived since that visit. A user with no read notification gets no row and sees every
-- notification as unseen, which the badge caps at 99+.
--
-- Runs after V118 so the watermark tuples name rows that survived aggregation.

INSERT INTO notification_seen_states
       (user_id, seen_activity_at, seen_id, previous_activity_at, previous_id)
SELECT DISTINCT ON (recipient_id)
       recipient_id, activity_at, id, activity_at, id
  FROM notifications
 WHERE read_at IS NOT NULL AND deleted_at IS NULL
 ORDER BY recipient_id, activity_at DESC, id DESC
ON CONFLICT (user_id) DO NOTHING;
