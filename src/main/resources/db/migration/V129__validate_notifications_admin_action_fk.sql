-- Validates the constraint V128 added NOT VALID. VALIDATE CONSTRAINT takes only a
-- SHARE UPDATE EXCLUSIVE lock on notifications, so it runs beside normal reads and writes.
ALTER TABLE notifications VALIDATE CONSTRAINT fk_notifications_admin_action;
