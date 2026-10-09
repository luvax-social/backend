CREATE TABLE user_mutes (
    user_id        UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    muted_user_id  UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mute_posts     BOOLEAN     NOT NULL,
    mute_stories   BOOLEAN     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, muted_user_id),
    CHECK (user_id <> muted_user_id),
    -- A row with both scopes off is an unmute; the service deletes it instead.
    CHECK (mute_posts OR mute_stories)
);
CREATE TRIGGER trg_user_mutes_updated_at BEFORE UPDATE ON user_mutes
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();

CREATE TABLE account_show_fewer (
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    author_id   UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, author_id),
    CHECK (user_id <> author_id)
);

CREATE TABLE interaction_limits (
    user_id                 UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    limit_non_followers     BOOLEAN     NOT NULL,
    limit_recent_followers  BOOLEAN     NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (limit_non_followers OR limit_recent_followers)
);
CREATE TRIGGER trg_interaction_limits_updated_at BEFORE UPDATE ON interaction_limits
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();
