-- Verification: the eight categories, the structured request, and the grant.
--
-- Three tables rather than one, because they answer three different questions and have three
-- different lifetimes. The categories are platform vocabulary. A request is what somebody asked for
-- and never changes after it is written. A grant is what the platform currently asserts about an
-- account, and it outlives the request that produced it.

-- The category vocabulary, following the enum-and-config contract V18 established: a closed set,
-- with the display metadata beside it.
--
-- These are VARCHAR keys against a config table rather than a PostgreSQL enum, which is the one
-- place this feature departs from the pattern used for support_category. The reason is that
-- support_category values change the behaviour of code - is_appeal gates an authorization rule -
-- while these are only ever matched, rendered and counted. Nothing branches on which one a grant
-- carries, so the constraint a foreign key gives is the constraint that is actually needed, and it
-- buys the ability to add a ninth category without an ALTER TYPE and its follow-up migration.
CREATE TABLE verification_categories (
    category_key    VARCHAR(50)     PRIMARY KEY,
    display_name    VARCHAR(100)    NOT NULL,
    covers          TEXT            NOT NULL,
    -- The server stores this and knows nothing about its shape. The client maps it to a glyph.
    -- Keeping the shape entirely client-side is what lets the badge be redrawn without a migration.
    icon_key        VARCHAR(50)     NOT NULL,
    is_enabled      BOOLEAN         NOT NULL DEFAULT TRUE,
    sort_order      SMALLINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

COMMENT ON COLUMN verification_categories.covers IS
    'The occupations this category is meant to cover, shown to the requester so they can pick without guessing. Never parsed.';

INSERT INTO verification_categories (category_key, display_name, covers, icon_key, sort_order) VALUES
    ('music',       'Music',                    'Singer, rapper, producer, DJ, musician, band',                 'music-note',   1),
    ('visual_arts', 'Visual arts',              'Painter, illustrator, photographer, designer, tattoo artist',  'palette',      2),
    ('writing',     'Writing and journalism',   'Author, poet, journalist, editor, blogger',                    'pen-nib',      3),
    ('science',     'Science and academia',     'Researcher, lecturer, physician, technical expert',            'flask',        4),
    ('screen',      'Screen and performance',   'Actor, director, filmmaker, comedian, stage performer',        'clapperboard', 5),
    ('sport',       'Sport',                    'Athlete, coach, referee, trainer',                             'medal',        6),
    ('business',    'Business and organisations','Founder, entrepreneur, brand, organisation, public body',     'briefcase',    7),
    ('gaming',      'Gaming and streaming',     'Streamer, esports player, content creator',                    'gamepad',      8);

-- The structured half of a verification ticket.
--
-- A child table keyed one-to-one to the ticket, rather than columns on support_tickets or free text
-- packed into its body. support_tickets carries a subject and a body, and this request carries a
-- category, a claimed name and seven evidence fields with a rule about how many must be filled. In
-- free text that rule is unenforceable at the data layer and the review surface cannot render the
-- fields it exists to weigh.
--
-- ON DELETE CASCADE from the ticket: the request has no meaning without the ticket that carries its
-- workflow, so it must not outlive it.
--
-- There is deliberately no file upload and no column for one. No identity documents, no passport,
-- no scan of anything. This is a design constraint, not an omission, and it is recorded here and in
-- support/DATA_RULES.md so a later contributor does not "complete" it.
CREATE TABLE verification_requests (
    ticket_id       UUID            PRIMARY KEY REFERENCES support_tickets(id) ON DELETE CASCADE,
    user_id         UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_key    VARCHAR(50)     NOT NULL REFERENCES verification_categories(category_key),
    claimed_name    VARCHAR(100)    NOT NULL,

    evidence_website            TEXT,
    evidence_other_profile      TEXT,
    evidence_email_domain       TEXT,
    evidence_published_work     TEXT,
    evidence_press              TEXT,
    evidence_official_listing   TEXT,
    evidence_note               TEXT,

    -- Denormalised so the three-field rule is enforceable by the database rather than only by the
    -- service. The service computes it from the seven columns it just wrote, so the two cannot
    -- disagree, and the CHECK is what makes a request with fewer than three unwritable by any path.
    evidence_field_count        SMALLINT    NOT NULL,

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT verification_requests_min_evidence CHECK (evidence_field_count >= 3),
    CONSTRAINT verification_requests_evidence_count_bound CHECK (evidence_field_count <= 7)
);

COMMENT ON TABLE verification_requests IS
    'The structured half of a verification_request support ticket. Immutable after insert: it records what was asked for, and the ticket beside it records what happened next.';

-- Whether an account holding a badge lost it by somebody deciding so, or by its own account status
-- changing underneath it.
--
-- The distinction has to survive in the row and not only in the audit log, because the moderator
-- reviewing a resubmission reads this table and needs to know whether a human judged this account
-- unworthy or whether a suspension simply swept the badge away.
CREATE TYPE verification_revocation_actor AS ENUM ('moderator', 'system');

-- The grant, and every grant that came before it.
--
-- Revocation is soft: the row stays with revoked_at set, so a moderator reviewing a resubmission
-- can see what was granted before and why it was withdrawn. Reviewing blind is how the same bad
-- grant gets made twice.
CREATE TABLE user_verifications (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_key        VARCHAR(50)     NOT NULL REFERENCES verification_categories(category_key),

    -- The ticket that produced this grant. Nullable because a grant can outlive its ticket only if
    -- the ticket is deleted, and because a future administrative grant may have no ticket at all.
    request_ticket_id   UUID            REFERENCES support_tickets(id) ON DELETE SET NULL,

    granted_by          UUID            REFERENCES users(id) ON DELETE SET NULL,
    granted_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    granted_action_id   UUID            REFERENCES admin_actions(id) ON DELETE SET NULL,

    revoked_at          TIMESTAMPTZ,
    -- Null for a system revocation, which is the point: admin_actions.admin_id is null for an
    -- automatic action by the same convention the discipline ladder and the suspension expiry sweep
    -- already follow.
    revoked_by          UUID            REFERENCES users(id) ON DELETE SET NULL,
    revocation_reason   TEXT,
    revocation_actor    verification_revocation_actor,
    revoked_action_id   UUID            REFERENCES admin_actions(id) ON DELETE SET NULL,

    -- revoked_at doubles as the flag, exactly as deleted_at, admin_removed_at and pinned_at do
    -- elsewhere in this schema, so there is no way to hold a revocation reason without a revocation.
    CONSTRAINT user_verifications_revocation_pair CHECK (
        (revoked_at IS NULL AND revocation_actor IS NULL AND revoked_by IS NULL
             AND revocation_reason IS NULL AND revoked_action_id IS NULL)
        OR (revoked_at IS NOT NULL AND revocation_actor IS NOT NULL)
    ),
    -- A system revocation is never attributed to a person, and a moderator revocation always is.
    CONSTRAINT user_verifications_system_has_no_actor CHECK (
        revocation_actor IS DISTINCT FROM 'system' OR revoked_by IS NULL
    )
);

COMMENT ON COLUMN user_verifications.revocation_actor IS
    'moderator when a person decided, system when an account status change swept the badge away. A status-driven revocation is not a verdict and must not read as one.';

-- users.is_verified and users.verified_category are the read side.
--
-- Denormalised onto users rather than joined at read time because UserSummaryResponse is the shared
-- identity projection embedded in every response that names an account - post author, comment,
-- reply, profile row, search result, suggestion, conversation participant, story owner, notification
-- actor - and it is built by one JPQL constructor expression over users alone. Two columns there
-- reach every one of those surfaces without a join on the hottest read in the application.
--
-- Maintained by a trigger, never by application code, for the reason the counter policy gives: a
-- derived value that a service can forget to write is a value that will eventually disagree with
-- its source. is_verified existed before this migration and was written by nothing.
CREATE OR REPLACE FUNCTION fn_sync_user_verification() RETURNS TRIGGER AS $$
DECLARE
    affected UUID;
BEGIN
    affected := COALESCE(NEW.user_id, OLD.user_id);
    UPDATE users u
       SET is_verified = v.active IS NOT NULL,
           verified_category = v.active
      FROM (
          SELECT (SELECT category_key
                    FROM user_verifications
                   WHERE user_id = affected AND revoked_at IS NULL
                   ORDER BY granted_at DESC
                   LIMIT 1) AS active
      ) v
     WHERE u.id = affected;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE users ADD COLUMN verified_category VARCHAR(50) REFERENCES verification_categories(category_key);

COMMENT ON COLUMN users.verified_category IS
    'Denormalised from the account''s active user_verifications row by trg_user_verification_sync. Never written by application code. Null exactly when is_verified is false.';

CREATE TRIGGER trg_user_verification_sync
    AFTER INSERT OR UPDATE OR DELETE ON user_verifications
    FOR EACH ROW EXECUTE FUNCTION fn_sync_user_verification();
