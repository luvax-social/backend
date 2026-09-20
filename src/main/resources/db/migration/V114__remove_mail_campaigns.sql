-- Flyway migration V114
-- Withdraws the mail campaign feature: its three tables, its two enum types, its two user_settings
-- columns, its moderation_action_configs row, and its value in admin_action_type.
--
-- Group chat was withdrawn the same way in V50 and this migration follows it: rows that are
-- deleted are archived first, because V30 deleted rows with no archive and neither migration
-- repeats that.
--
-- Every archived enum column is declared TEXT rather than against its enum type. Both campaign
-- enum types are dropped below, so a column typed against one of them would not survive this
-- migration. V50 declares archived_group_messages.message_type TEXT for the same reason.
--
-- Runs in one transaction. It creates no index, so unlike the sixteen index migrations here it
-- needs no .sql.conf sidecar and no CONCURRENTLY build.
--
-- The admin_action_type swap at the end rewrites admin_actions under an ACCESS EXCLUSIVE lock.
-- That cost was accepted rather than leaving the value dormant, because send_mail_campaign was
-- never reachable: the Java enum AdminActionType has never declared it, and no campaign class
-- referenced AdminActionRecorder or admin_actions. Leaving it would preserve a value that never
-- worked, and the seed's enum-coverage assertion would keep demanding five rows for it.
-- The swap is written plainly because admin_actions.action_type is the only column of this type,
-- it is NOT NULL with no DEFAULT, no index references it, and no view or function depends on it.

-- Guard, before anything is written.
--
-- admin_actions is append-only: docs/modules/admin/DATA_RULES.md states its rows are never updated
-- or deleted, and AdminActionRepository exposes read and insert only. A migration must not quietly
-- delete audit rows to make a type swap convenient, so if any row holds the retired value this
-- refuses and a person decides. No application path can have produced one; a hand-written INSERT
-- could. Letting the USING cast below discover it instead would surface as an opaque cast failure
-- rather than a stated refusal.
DO $$
DECLARE
    stray_rows INT;
BEGIN
    SELECT COUNT(*) INTO stray_rows
      FROM admin_actions
     WHERE action_type = 'send_mail_campaign';

    IF stray_rows > 0 THEN
        RAISE EXCEPTION
            'V114 refuses to run: % admin_actions row(s) hold send_mail_campaign. admin_actions is append-only, so these are reconciled by hand before this migration is applied.',
            stray_rows;
    END IF;
END $$;

CREATE TABLE archived_mail_templates (
    template_key    VARCHAR(100)    PRIMARY KEY,
    display_name    VARCHAR(150),
    description     TEXT,
    body            TEXT,
    sort_order      SMALLINT,
    is_enabled      BOOLEAN,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    archived_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE archived_mail_campaigns (
    id              UUID            PRIMARY KEY,
    template_key    VARCHAR(100),
    subject         VARCHAR(200),
    body            TEXT,
    created_by      UUID,
    status          TEXT,
    scheduled_at    TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    recipient_count INT,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    archived_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE TABLE archived_mail_campaign_recipients (
    id                UUID          PRIMARY KEY,
    campaign_id       UUID          NOT NULL,
    user_id           UUID,
    resolved_email    VARCHAR(255),
    status            TEXT,
    email_delivery_id UUID,
    created_at        TIMESTAMPTZ,
    archived_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

INSERT INTO archived_mail_templates
    (template_key, display_name, description, body, sort_order, is_enabled, created_at, updated_at)
SELECT template_key, display_name, description, body, sort_order, is_enabled, created_at, updated_at
  FROM mail_templates;

INSERT INTO archived_mail_campaigns
    (id, template_key, subject, body, created_by, status, scheduled_at, sent_at, recipient_count,
     created_at, updated_at)
SELECT id, template_key, subject, body, created_by, status::text, scheduled_at, sent_at,
       recipient_count, created_at, updated_at
  FROM mail_campaigns;

INSERT INTO archived_mail_campaign_recipients
    (id, campaign_id, user_id, resolved_email, status, email_delivery_id, created_at)
SELECT id, campaign_id, user_id, resolved_email, status::text, email_delivery_id, created_at
  FROM mail_campaign_recipients;

DROP TABLE mail_campaign_recipients;
DROP TABLE mail_campaigns;
DROP TABLE mail_templates;

DROP TYPE mail_campaign_recipient_status;
DROP TYPE mail_campaign_status;

-- Dropping the column also drops uq_user_settings_unsubscribe_token, the partial unique index V103
-- built over it, so there is no separate DROP INDEX here.
ALTER TABLE user_settings DROP COLUMN unsubscribe_token;
ALTER TABLE user_settings DROP COLUMN email_opt_out;

-- moderation_action_configs.action_key is VARCHAR(100) rather than the enum, so this is an ordinary
-- row delete. It runs before the type is recreated because the config table is the metadata layer
-- for the enum and must not name a value the type no longer declares.
DELETE FROM moderation_action_configs WHERE action_key = 'send_mail_campaign';

ALTER TYPE admin_action_type RENAME TO admin_action_type_old;

CREATE TYPE admin_action_type AS ENUM (
    'ban_user',
    'unban_user',
    'suspend_user',
    'unsuspend_user',
    'remove_post',
    'restore_post',
    'remove_comment',
    'restore_comment',
    'resolve_report',
    'dismiss_report',
    'change_user_role',
    'warn_user',
    'revoke_warning',
    'issue_strike',
    'revoke_strike',
    'escalate_report',
    'force_logout',
    'create_hashtag',
    'edit_hashtag',
    'ban_hashtag',
    'unban_hashtag',
    'delete_hashtag',
    'remove_story',
    'restore_story',
    'remove_message',
    'restore_message',
    'revoke_session',
    'pin_hashtag',
    'unpin_hashtag',
    'respond_support_ticket',
    'reject_support_ticket',
    'escalate_support_ticket',
    'grant_verification',
    'reject_verification',
    'revoke_verification'
);

ALTER TABLE admin_actions
    ALTER COLUMN action_type TYPE admin_action_type
    USING action_type::text::admin_action_type;

DROP TYPE admin_action_type_old;

COMMENT ON TABLE archived_mail_templates IS
    'Withdrawn with the mail campaign feature in V114. Retained so a later reader can see what the three seeded Markdown samples held.';
COMMENT ON TABLE archived_mail_campaigns IS
    'Withdrawn with the mail campaign feature in V114. status is TEXT because mail_campaign_status was dropped in the same migration.';
COMMENT ON TABLE archived_mail_campaign_recipients IS
    'Withdrawn with the mail campaign feature in V114. status is TEXT because mail_campaign_recipient_status was dropped in the same migration.';
