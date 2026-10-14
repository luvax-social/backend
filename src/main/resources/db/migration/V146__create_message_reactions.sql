-- One emoji reaction per user per message. The surrogate id exists because a reaction is
-- reportable and a report's entity_id is a single UUID; UNIQUE (message_id, user_id) enforces the
-- one-per-user rule. reacted_at is set by the upsert statement with NOW() and moves when the
-- emoji changes, because a changed reaction is a new reaction time. admin_removed_at is the
-- moderation tombstone, independent of the message's own tombstones.
-- The supporting indexes are built CONCURRENTLY in a later migration.
CREATE TABLE message_reactions (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id        UUID        NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id           UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji             VARCHAR(32) NOT NULL,
    reacted_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    admin_removed_at  TIMESTAMPTZ NULL,
    UNIQUE (message_id, user_id)
);
