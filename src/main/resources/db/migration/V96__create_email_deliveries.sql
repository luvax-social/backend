-- The send log for outbound mail.
--
-- Nothing recorded what the system sent before this table. The Resend response was discarded at the
-- point of the call, so a provider message id existed only inside the provider's own dashboard and
-- there was no way to answer whether a given account was actually told about a moderation decision.
-- That question is the reason this table exists: a ban notice is a claim the platform makes to a
-- user, and a claim with no record is not auditable.
--
-- One row per send attempt, never rewritten except to move status forward and stamp sent_at.
--
-- recipient_user_id is nullable and ON DELETE SET NULL. The log outlives the account: a hard delete
-- must not erase the evidence that a notice was sent. recipient_email is stored alongside it and is
-- NOT NULL, so a row whose user is gone still says where the message went.
--
-- admin_action_id is nullable because auth mail has no audit row behind it. When present it ties a
-- delivery to the exact moderation decision that caused it, which is what makes the pair legible in
-- a dispute.
--
-- provider_message_id is nullable: it exists only after the provider accepts, so it is NULL for
-- every status except 'sent'.

CREATE TYPE email_delivery_status AS ENUM (
    'pending',
    'sent',
    'failed',
    'throttled',
    'skipped'
);

COMMENT ON TYPE email_delivery_status IS
    'Observable outcomes of one send attempt. pending is written before the provider call; sent carries a provider_message_id; failed carries error_text; throttled means the per-recipient window rejected it before any call; skipped means the recipient was ineligible, such as a soft-deleted account.';

CREATE TABLE email_deliveries (
    id                  UUID                    PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_user_id   UUID                    REFERENCES users(id) ON DELETE SET NULL,
    recipient_email     VARCHAR(255)            NOT NULL,
    template_key        VARCHAR(100)            NOT NULL,
    admin_action_id     UUID                    REFERENCES admin_actions(id),
    status              email_delivery_status   NOT NULL DEFAULT 'pending',
    provider_message_id VARCHAR(255),
    error_text          TEXT,
    attempt_count       INT                     NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    created_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMPTZ
);

COMMENT ON COLUMN email_deliveries.recipient_user_id IS
    'Nullable and ON DELETE SET NULL so a hard-deleted account does not erase the record that mail was sent to it; recipient_email preserves the destination.';

COMMENT ON COLUMN email_deliveries.admin_action_id IS
    'The moderation decision this delivery announces, or NULL for mail with no audit row behind it, such as auth mail.';

COMMENT ON COLUMN email_deliveries.provider_message_id IS
    'Identifier returned by the provider on acceptance. NULL for every status other than sent.';

COMMENT ON COLUMN email_deliveries.attempt_count IS
    'Provider calls made for this row. Stays 0 for a row that never reached a call, which is what separates a throttled or skipped send from one the provider rejected.';

-- No secondary index. The table has exactly one writer and no read path in this release, so any
-- index added now would be chosen against a query that does not exist yet. The natural ones, by
-- recipient_user_id and by created_at, belong with whichever surface first reads this log and must
-- be built CREATE INDEX CONCURRENTLY with a .sql.conf sidecar when that happens.
