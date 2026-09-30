-- notifications.admin_action_id was an unconstrained reference. Orphans, if any, are archived and
-- cleared before the constraint is added, per base.md's rule for row-overwriting migrations. The
-- constraint is added NOT VALID so this migration never scans the table under a write-blocking
-- lock; V129 validates it in a transaction of its own.
CREATE TABLE archived_notification_admin_action_orphans AS
SELECT n.id AS notification_id, n.admin_action_id, NOW() AS archived_at
FROM notifications n
WHERE n.admin_action_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM admin_actions a WHERE a.id = n.admin_action_id);

UPDATE notifications n SET admin_action_id = NULL
WHERE n.admin_action_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM admin_actions a WHERE a.id = n.admin_action_id);

ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_admin_action
    FOREIGN KEY (admin_action_id) REFERENCES admin_actions (id) ON DELETE SET NULL NOT VALID;
