-- Derived read model: how strongly each user leans toward each hashtag, over a bounded window.
--
-- Three surfaces consume it - personalised trending, composer suggestions, and the interest
-- similarity candidate source planned next - so it is built once here rather than three times.
--
-- WHY (user_id, hashtag_id) IN PLACE, NOT VERSIONED BY WINDOW
--
-- Versioning would keep one row per user per hashtag per window and let a run be compared with or
-- rolled back to its predecessor. It is rejected because nothing reads a historical window: all
-- three consumers ask "what does this user lean toward now". The cost is real - row count
-- multiplies by the number of retained windows, on a table already sized users x hashtags - and it
-- would need its own retention job to stop growing without bound. The rollback argument is weak
-- here specifically: the job is a full recompute of a rolling window, so a bad run is corrected by
-- the next run twelve hours later rather than by restoring the previous one.
--
-- The composite primary key is also what makes the job idempotent. This system assumes a single
-- application instance and has no distributed scheduler lock, exactly as platform_stats does, so a
-- double run must be harmless rather than duplicative. ON CONFLICT DO UPDATE against this key is
-- what provides that.
--
-- window_start and window_end are kept on the row even though the score is not versioned by them,
-- because a score is meaningless without the interval it was computed over, and because a stale
-- row left behind by a job that stopped running is otherwise undetectable.
--
-- No CREATE INDEX CONCURRENTLY here: the only index this migration creates is the primary key's,
-- which is implicit in CREATE TABLE on a table that does not yet exist and therefore locks nothing.
-- The read index is built concurrently in V91, where the statement is a real CREATE INDEX.

CREATE TABLE user_hashtag_affinity (
    user_id      UUID        NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    hashtag_id   UUID        NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,

    -- Share of the user's total decayed engagement that went to this hashtag, so the values for
    -- one user sum to approximately 1. Normalising per user is what stops a heavy user's raw
    -- volume dominating a light user's in the blend that reads this table; without it the ranking
    -- would measure activity rather than interest.
    score        NUMERIC(10,8) NOT NULL CHECK (score >= 0 AND score <= 1),

    -- Decayed weight before normalisation, and the number of events behind it. Neither is read by
    -- the application; both exist so a score that looks wrong can be explained without re-running
    -- the job against a window that has since moved.
    weight       NUMERIC(14,6) NOT NULL CHECK (weight >= 0),
    event_count  INTEGER       NOT NULL CHECK (event_count >= 0),

    window_start TIMESTAMPTZ NOT NULL,
    window_end   TIMESTAMPTZ NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, hashtag_id),
    CONSTRAINT user_hashtag_affinity_window_order CHECK (window_end > window_start)
);

COMMENT ON TABLE user_hashtag_affinity IS
    'Per-user hashtag interest scores derived from user_events joined to post_hashtags over a '
    'bounded window. Rebuildable: truncating it costs only the next scheduled recompute.';

COMMENT ON COLUMN user_hashtag_affinity.score IS
    'Normalised share of the user total, in [0,1]; comparable across users of different activity '
    'volumes.';
