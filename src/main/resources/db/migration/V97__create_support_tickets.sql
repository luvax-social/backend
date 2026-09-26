-- The support centre: one request, one response.
--
-- Nothing resembling a ticket, appeal or case existed anywhere before this. The only user-facing
-- moderation surface was a read-only warning list, so a banned account had no route to contest a
-- decision at all - and, because TokenPrincipalResolverImpl admits only ACTIVE accounts, no route
-- to any authenticated endpoint either. That is why this table carries contact_email and a source
-- discriminator rather than relying on a session to identify the person writing.
--
-- There is deliberately no message table. A ticket is one message from a user and one reply from
-- staff, not a thread. Adding turns later means adding a child table, not reshaping this one.
--
-- Column mutability is split the way Report and AdminAction split theirs: everything the user wrote
-- is immutable after insert, and only the staff workflow columns are ever updated. The entity
-- enforces the same split with updatable = false, so neither layer alone is load-bearing.

CREATE TYPE support_category AS ENUM (
    'appeal_ban',
    'appeal_suspension',
    'appeal_warning_strike',
    'appeal_content_removal',
    'account_access',
    'account_data',
    'bug_report',
    'safety_concern',
    'other'
);

COMMENT ON TYPE support_category IS
    'What the ticket is about. The four appeal_ values are the restricted set: only an administrator may respond to or close one, because unban, unsuspend, revoke_warning and revoke_strike are all administrator-only actions and a moderator closing an appeal would be issuing a verdict they cannot execute.';

CREATE TYPE support_ticket_status AS ENUM (
    'pending_confirmation',
    'open',
    'in_progress',
    'escalated',
    'answered',
    'rejected'
);

COMMENT ON TYPE support_ticket_status IS
    'pending_confirmation belongs to the public form alone: the row exists but is invisible to staff until the submitter proves control of the address. answered and rejected are terminal, and terminal is what the one-open-ticket guard treats as closed.';

CREATE TYPE support_source AS ENUM (
    'authenticated',
    'signed_link',
    'public_form'
);

COMMENT ON TYPE support_source IS
    'How the ticket arrived. signed_link means a single-use token from a moderation mail authorised exactly this one ticket against one audit row; it never minted a session.';

CREATE TABLE support_tickets (
    id                  UUID                    PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID                    REFERENCES users(id) ON DELETE SET NULL,
    contact_email       VARCHAR(255)            NOT NULL,
    category            support_category        NOT NULL,
    subject             VARCHAR(200)            NOT NULL,
    body                TEXT                    NOT NULL,
    status              support_ticket_status   NOT NULL DEFAULT 'open',
    source              support_source          NOT NULL,
    admin_action_id     UUID                    REFERENCES admin_actions(id) ON DELETE SET NULL,
    assigned_to         UUID                    REFERENCES users(id) ON DELETE SET NULL,
    assigned_at         TIMESTAMPTZ,
    staff_response      TEXT,
    internal_note       TEXT,
    responded_by        UUID                    REFERENCES users(id) ON DELETE SET NULL,
    responded_at        TIMESTAMPTZ,
    escalated_by        UUID                    REFERENCES users(id) ON DELETE SET NULL,
    escalated_at        TIMESTAMPTZ,
    escalation_reason   TEXT,
    created_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ             NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN support_tickets.user_id IS
    'Nullable: the public form produces a ticket before any account is resolved, and ON DELETE SET NULL keeps the ticket after a hard delete. contact_email is the durable channel.';

COMMENT ON COLUMN support_tickets.contact_email IS
    'Where the response goes. Captured even for an authenticated ticket, because the account may be banned and this address is then the only channel that reaches the person.';

COMMENT ON COLUMN support_tickets.admin_action_id IS
    'The audit row this ticket appeals against, populated when the ticket arrived through a signed link. Also the input to the conflict-of-interest rule: the staff member who wrote that row may not act on this ticket.';

COMMENT ON COLUMN support_tickets.internal_note IS
    'Staff-only. Never rendered into any user-facing response and never carried into any mail. staff_response is the field that reaches the user.';

COMMENT ON COLUMN support_tickets.assigned_to IS
    'Set by a guarded update whose predicate re-checks that the ticket is still unassigned, so a second claimer is refused rather than silently overwriting the first.';

CREATE TRIGGER trg_support_tickets_updated_at
    BEFORE UPDATE ON support_tickets
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();

-- Display metadata for the category enum, following the contract V18 established for
-- report_reason_configs: the enum is the constraint layer and this table is the metadata layer.
-- Exposed read-only through GET /api/v1/config/vocabularies alongside the other three.
CREATE TABLE support_category_configs (
    category_key        VARCHAR(100)    PRIMARY KEY,
    display_name        VARCHAR(100)    NOT NULL,
    description         TEXT,
    -- TRUE for the four appeal_ values. A client uses it to decide whether to offer the category
    -- outside an appeal flow; the server never trusts it, and the authorization rule reads the
    -- category enum itself.
    is_appeal           BOOLEAN         NOT NULL DEFAULT FALSE,
    -- TRUE when the category may be chosen on the public form. Appeals may not: an appeal needs an
    -- audit row to appeal against, which only a signed link supplies.
    allows_public_form  BOOLEAN         NOT NULL DEFAULT TRUE,
    is_enabled          BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_order          SMALLINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

INSERT INTO support_category_configs (category_key, display_name, description, is_appeal, allows_public_form, is_enabled, sort_order) VALUES
    ('appeal_ban',             'Appeal a ban',               'Ask for a banned account to be reviewed.',                   TRUE,  FALSE, TRUE, 1),
    ('appeal_suspension',      'Appeal a suspension',        'Ask for a suspended account to be reviewed.',                TRUE,  FALSE, TRUE, 2),
    ('appeal_warning_strike',  'Appeal a warning or strike', 'Ask for a warning or a strike to be reviewed.',              TRUE,  FALSE, TRUE, 3),
    ('appeal_content_removal', 'Appeal a content removal',   'Ask for removed content to be reviewed.',                    TRUE,  FALSE, TRUE, 4),
    ('account_access',         'Account access',             'Trouble signing in or recovering an account.',               FALSE, TRUE,  TRUE, 5),
    ('account_data',           'Account data',               'Questions about the data held on an account.',               FALSE, TRUE,  TRUE, 6),
    ('bug_report',             'Report a bug',               'Something in the product is not working.',                   FALSE, TRUE,  TRUE, 7),
    ('safety_concern',         'Safety concern',             'Raise a safety issue a content report does not cover.',      FALSE, TRUE,  TRUE, 8),
    ('other',                  'Something else',             'Anything the other categories do not cover.',                FALSE, TRUE,  TRUE, 99);
