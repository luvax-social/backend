-- Serves the conversation list's latest-reaction read, which looks up the newest reaction of each
-- listed conversation. It also covers the ON DELETE CASCADE foreign key added in V148, so deleting
-- a conversation does not scan message_reactions.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_message_reactions_conversation_reacted
    ON message_reactions (conversation_id, reacted_at DESC);

-- Replaces idx_message_reactions_reacted_at (V147). That index existed only to start the old
-- latest-reaction read from reactions newer than a conversation's last message; nothing reads it
-- now, and an unused index still costs every reaction write. The replacement above is built first
-- so the read is never without an index in between.
DROP INDEX CONCURRENTLY IF EXISTS idx_message_reactions_reacted_at;
