-- The boolean is archived before it is dropped, per the destructive-change rule.
CREATE TABLE archived_user_settings_allow_message_requests (
    user_id                 UUID        PRIMARY KEY,
    allow_message_requests  BOOLEAN     NOT NULL,
    archived_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO archived_user_settings_allow_message_requests (user_id, allow_message_requests)
SELECT user_id, allow_message_requests FROM user_settings;

ALTER TABLE user_settings ADD COLUMN message_permission message_permission NOT NULL DEFAULT 'everyone';

UPDATE user_settings
SET message_permission = CASE
    WHEN allow_message_requests THEN 'everyone'::message_permission
    ELSE 'following'::message_permission
END;

ALTER TABLE user_settings DROP COLUMN allow_message_requests;
