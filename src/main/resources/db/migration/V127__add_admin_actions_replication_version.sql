-- admin_actions is replicated to ClickHouse. The application never updates a row, but PostgreSQL
-- does: admin_id, target_user_id and report_id are ON DELETE SET NULL. row_version lets the
-- replica keep the newest state (ReplacingMergeTree(row_version)), and the AFTER trigger enqueues
-- the change in the same transaction as the update, so a cascade can never be missed.
ALTER TABLE admin_actions ADD COLUMN row_version BIGINT NOT NULL DEFAULT 1;

CREATE FUNCTION fn_admin_actions_bump_row_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.row_version := OLD.row_version + 1;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_admin_actions_bump_row_version
    BEFORE UPDATE ON admin_actions
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION fn_admin_actions_bump_row_version();

CREATE FUNCTION fn_admin_actions_enqueue_replication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_event_id UUID := gen_random_uuid();
    v_now TEXT := to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
BEGIN
    INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, routing_key, payload)
    VALUES (v_event_id, 'admin_action', NEW.id, 'admin.action.changed.v1', 'admin.action.changed.v1',
            jsonb_build_object(
                'eventId', v_event_id,
                'eventType', 'admin.action.changed.v1',
                'occurredAt', v_now,
                'actorId', NULL,
                'aggregateType', 'admin_action',
                'aggregateId', NEW.id,
                'data', jsonb_build_object('adminActionId', NEW.id, 'rowVersion', NEW.row_version)));
    RETURN NULL;
END $$;

CREATE TRIGGER trg_admin_actions_enqueue_replication
    AFTER UPDATE ON admin_actions
    FOR EACH ROW WHEN (OLD.* IS DISTINCT FROM NEW.*)
    EXECUTE FUNCTION fn_admin_actions_enqueue_replication();
