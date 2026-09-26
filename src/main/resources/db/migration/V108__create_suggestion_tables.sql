-- People you may know: the read model, the dismissals, and the account-level opt-out.

-- The precomputed suggestion list, one row per (viewer, suggested) pair.
--
-- A read model, not a source of truth: every row here is derivable from follows, user_events,
-- user_hashtag_affinity and the recommender, and the job that writes it may be re-run at any time
-- against an empty table without losing anything.
--
-- The composite primary key is the point. Reading a viewer's list is a range scan on the leading
-- column, and the twelve-hourly job's ON CONFLICT DO UPDATE needs exactly this key to be idempotent
-- on a double run. There is no distributed scheduler lock in this application, and this job
-- inherits that assumption exactly as the affinity job does: a second instance running the job
-- concurrently would produce the same rows, not duplicates.
CREATE TABLE user_suggestions (
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    suggested_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Position in the blended list at the time the job ran, 1-based. Stored rather than recomputed
    -- so a read can order without re-deriving the fusion, and so a plan can use the index below.
    rank            SMALLINT    NOT NULL,

    -- The fused reciprocal-rank score. Kept for explainability rather than for ordering: rank is
    -- what orders, and this is what lets somebody ask why one account outranked another.
    score           NUMERIC(12, 8) NOT NULL,

    -- Which sources contributed, comma-separated, from the fixed set graph|gorse|affinity|verified.
    -- Text rather than an enum array because nothing branches on it; it exists so a degraded blend
    -- is visible in the data rather than only inferable from an empty Gorse.
    sources         TEXT        NOT NULL,

    computed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, suggested_id),

    -- An account is never suggested to itself. Enforced here as well as in the job's SQL, because
    -- the read-time filter should never be the only thing standing between a bug and a user seeing
    -- themselves in their own suggestions.
    CONSTRAINT user_suggestions_not_self CHECK (user_id <> suggested_id),
    CONSTRAINT user_suggestions_rank_positive CHECK (rank > 0)
);

COMMENT ON TABLE user_suggestions IS
    'Precomputed people-you-may-know candidates. Rebuildable from source at any time. Every exclusion rule is re-applied at read time, because a twelve-hour cycle would otherwise keep suggesting an account the viewer followed this morning.';

-- A suggestion the viewer removed, permanently.
--
-- Not a block. The dismissed account still appears in search, on profiles, in the follow graph and
-- everywhere else it would normally appear; it is only barred from this one surface.
--
-- The composite primary key is chosen so the read-time filter is an index lookup rather than a
-- scan: the suggestion read joins on (user_id, dismissed_id), which is the key itself.
CREATE TABLE suggestion_dismissals (
    user_id         UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    dismissed_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, dismissed_id),

    CONSTRAINT suggestion_dismissals_not_self CHECK (user_id <> dismissed_id)
);

COMMENT ON TABLE suggestion_dismissals IS
    'Permanent per-viewer suppression of one account from people-you-may-know. Never a block: it changes no other surface.';

-- The account-level opt-out: do not suggest my account to other people.
--
-- A column on user_settings rather than a new table, because every account has exactly one
-- user_settings row from creation and the read of that row is deliberately strict - a 404 there
-- means the invariant is broken, not that the account has no preferences. NOT NULL DEFAULT TRUE
-- backfills every existing row in place, so that read keeps returning exactly one row and does not
-- have to be weakened to tolerate a missing value.
ALTER TABLE user_settings
    ADD COLUMN suggestible BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN user_settings.suggestible IS
    'False when the account has asked not to be offered in other people''s suggestions. Applied at read time, not only in the precompute job.';
