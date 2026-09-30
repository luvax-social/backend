-- One row per operator-triggered Gorse rebuild, identified by the token the operator chose. The
-- checkpoint is the last PostgreSQL id fully sent in the current phase, so a restart resumes
-- after it; every Gorse write the tool makes is an overwrite, so repeating the batch in flight
-- when a crash happened changes nothing. Progress counts are written by the job itself; they are
-- job state, not denormalized counters of another table.
CREATE TABLE gorse_rebuild_runs (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    token            VARCHAR(100) NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    phase            VARCHAR(20)  NOT NULL,
    checkpoint_id    UUID,
    users_sent       BIGINT       NOT NULL DEFAULT 0,
    items_sent       BIGINT       NOT NULL DEFAULT 0,
    feedback_sent    BIGINT       NOT NULL DEFAULT 0,
    feedback_skipped BIGINT       NOT NULL DEFAULT 0,
    last_error       TEXT,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at      TIMESTAMPTZ,
    PRIMARY KEY (id),
    UNIQUE (token),
    CONSTRAINT chk_gorse_rebuild_runs_status CHECK (status IN ('RUNNING', 'FAILED', 'FAILED_VERIFICATION', 'DONE')),
    CONSTRAINT chk_gorse_rebuild_runs_phase CHECK (phase IN ('PREFLIGHT', 'PURGE', 'USERS', 'ITEMS', 'FEEDBACK', 'VERIFY', 'DONE')),
    CONSTRAINT chk_gorse_rebuild_runs_counts CHECK (users_sent >= 0 AND items_sent >= 0 AND feedback_sent >= 0 AND feedback_skipped >= 0)
);

CREATE TRIGGER trg_gorse_rebuild_runs_updated_at
    BEFORE UPDATE ON gorse_rebuild_runs
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();
