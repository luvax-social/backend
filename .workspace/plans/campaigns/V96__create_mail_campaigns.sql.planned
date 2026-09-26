-- Custom mail campaigns, and the opt-out that makes sending them defensible.
--
-- A campaign mail is marketing, not transactional. Nothing in this system had an email opt-out of
-- any kind before now: user_settings carries five notify_ flags and no mail path reads any of them.
-- email_opt_out below is the first, and the campaign send path is the only thing that reads it.
-- Auth mail and moderation mail deliberately ignore it: a password reset or a ban notice is not
-- something a user can unsubscribe from.
--
-- A template is a sample, not a document. An administrator opens one, its Markdown loads into an
-- editor, they change it, and the change is saved to the campaign. It is never written back, so a
-- second administrator opening the same template gets the original sample. That is why the body
-- lives on mail_campaigns as a snapshot rather than as a reference to mail_templates.

ALTER TABLE user_settings
    ADD COLUMN email_opt_out BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN user_settings.email_opt_out IS
    'Suppresses campaign mail only. Auth mail and moderation mail ignore it entirely: a password reset and a ban notice are not marketing and are not opt-out-able.';

-- A per-user secret rather than a signed value. A signed token would need the signing key to stay
-- stable forever or every previously mailed link breaks, and rotating that key would silently
-- invalidate every unsubscribe link already in someone's inbox. A stored secret can be rotated per
-- user, is revoked by regenerating one row, and needs no session to verify - which is the actual
-- requirement, because the recipient clicking it is logged out and may well be banned.
ALTER TABLE user_settings
    ADD COLUMN unsubscribe_token CHAR(64);

COMMENT ON COLUMN user_settings.unsubscribe_token IS
    'SHA-256 hex of the per-user unsubscribe secret. Null until first needed; the send path generates one lazily. Verified without a session, because the recipient following the link from a mail client has none.';

CREATE TYPE mail_campaign_status AS ENUM (
    'draft',
    'scheduled',
    'sending',
    'sent',
    'cancelled',
    'failed'
);

COMMENT ON TYPE mail_campaign_status IS
    'sending is the claim state. The scheduler moves scheduled to sending with a conditional update, so only one instance can ever claim a campaign; see mail_campaigns for why that matters here and not for platform_stats.';

CREATE TYPE mail_campaign_recipient_status AS ENUM (
    'pending',
    'queued',
    'skipped_opted_out',
    'failed'
);

COMMENT ON TYPE mail_campaign_recipient_status IS
    'skipped_opted_out records an exclusion rather than dropping the row, so an administrator can see who was left out and why instead of wondering where a recipient went.';

CREATE TABLE mail_templates (
    template_key    VARCHAR(100)    PRIMARY KEY,
    display_name    VARCHAR(150)    NOT NULL,
    description     TEXT,
    body            TEXT            NOT NULL,
    sort_order      SMALLINT        NOT NULL DEFAULT 0,
    is_enabled      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE mail_templates IS
    'Read-only samples seeded here. There is no administrator-facing create, update or delete in this release; campaigns consume a template by copying its body, so adding that CRUD later changes nothing about how campaigns read it.';

COMMENT ON COLUMN mail_templates.body IS
    'Markdown, not HTML. The send and preview paths run one pipeline: CommonMark to HTML, then the OWASP sanitizer allowlist, then injection into the shared Thymeleaf layout.';

CREATE TRIGGER trg_mail_templates_updated_at
    BEFORE UPDATE ON mail_templates
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();

CREATE TABLE mail_campaigns (
    id              UUID                    PRIMARY KEY DEFAULT gen_random_uuid(),
    template_key    VARCHAR(100)            REFERENCES mail_templates(template_key) ON DELETE SET NULL,
    subject         VARCHAR(200)            NOT NULL,
    body            TEXT                    NOT NULL,
    created_by      UUID                    REFERENCES users(id) ON DELETE SET NULL,
    status          mail_campaign_status    NOT NULL DEFAULT 'draft',
    scheduled_at    TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    recipient_count INT                     NOT NULL DEFAULT 0 CHECK (recipient_count >= 0),
    created_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN mail_campaigns.template_key IS
    'Which sample this campaign started from, for reporting only. ON DELETE SET NULL because the campaign body is an independent snapshot and outlives the template it was copied from.';

COMMENT ON COLUMN mail_campaigns.body IS
    'Markdown snapshot, immutable once the campaign leaves draft. A campaign is the record of what was actually sent, so editing it after the fact would make that record a lie.';

COMMENT ON COLUMN mail_campaigns.status IS
    'The scheduler claims a campaign with a conditional UPDATE from scheduled to sending. That single-row transition is what makes a double send impossible: this codebase has no distributed scheduler lock, and unlike platform_stats a second run here would mail real people twice.';

CREATE TRIGGER trg_mail_campaigns_updated_at
    BEFORE UPDATE ON mail_campaigns
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();

CREATE TABLE mail_campaign_recipients (
    id                  UUID                            PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id         UUID                            NOT NULL REFERENCES mail_campaigns(id) ON DELETE CASCADE,
    user_id             UUID                            REFERENCES users(id) ON DELETE SET NULL,
    resolved_email      VARCHAR(255),
    status              mail_campaign_recipient_status  NOT NULL DEFAULT 'pending',
    email_delivery_id   UUID                            REFERENCES email_deliveries(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ                     NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN mail_campaign_recipients.resolved_email IS
    'The address as it stood when the campaign was sent, not when it was drafted. Null while the recipient is still pending, and null for a recipient skipped before any address was read.';

COMMENT ON COLUMN mail_campaign_recipients.email_delivery_id IS
    'Links to the send log row so a campaign recipient can be traced to the provider message id. Null for a skipped or still-pending recipient, which never reached a send.';

-- One row per user per campaign. Without this a mistaken double submit would mail the same person
-- twice from one campaign, which is the failure this whole table exists to make visible.
ALTER TABLE mail_campaign_recipients
    ADD CONSTRAINT uq_mail_campaign_recipients_campaign_user UNIQUE (campaign_id, user_id);

INSERT INTO mail_templates (template_key, display_name, description, body, sort_order, is_enabled) VALUES
    ('announcement', 'Product announcement', 'A general announcement to selected accounts.',
     E'# Something new on Luvax\n\nHi {{username}},\n\nWe have been working on something we think you will like.\n\n- Point one\n- Point two\n\nLet us know what you think.\n',
     1, TRUE),
    ('policy_update', 'Policy update', 'Tell selected accounts that a policy has changed.',
     E'# An update to our policies\n\nHi {{fullName}},\n\nWe are changing how we handle a few things. Here is what is different and when it takes effect.\n\n> Summarise the change here.\n\nThe full policy is on our website.\n',
     2, TRUE),
    ('account_check_in', 'Account check-in', 'A light touch message to a small hand-picked list.',
     E'# Checking in\n\nHi {{username}},\n\nWe noticed you have been away for a while. Nothing is wrong with your account - we just wanted to say hello.\n\nCome back any time.\n',
     3, TRUE);
