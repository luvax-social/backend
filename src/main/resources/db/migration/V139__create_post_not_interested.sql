CREATE TABLE post_not_interested (
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id     UUID        NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, post_id)
);
