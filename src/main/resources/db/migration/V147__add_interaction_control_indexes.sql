-- Supporting indexes for the interaction control reads. Each is built CONCURRENTLY so the build
-- never blocks writes to a table that is already in use.

-- Serves the limit rule's check for an accepted follow no older than seven days.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_follows_following_accepted
    ON follows (following_id, accepted_at) WHERE status = 'accepted';

-- Serves the muted accounts list, newest change first, with the muted account as the tiebreaker.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_mutes_user_updated
    ON user_mutes (user_id, updated_at DESC, muted_user_id DESC);

-- Serves the personal hidden words list, newest first.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_hidden_words_user
    ON user_hidden_words (user_id, created_at DESC);

-- Serves the post owner's list of hidden comments; partial because every other comment is shown.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_comments_post_hidden
    ON comments (post_id, created_at DESC, id DESC)
    WHERE moderation_status = 'hidden' AND deleted_at IS NULL AND admin_removed_at IS NULL;

-- Serves the requests and hidden requests folders, which list only pending requests.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_conversations_request_inbox
    ON conversations (request_recipient_id, request_hidden, last_message_at DESC, id DESC)
    WHERE request_status = 'pending';

-- Covers the ON DELETE SET NULL foreign key, so deleting an account does not scan conversations.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_conversations_request_recipient
    ON conversations (request_recipient_id) WHERE request_recipient_id IS NOT NULL;

-- Serves message history, which includes tombstones. idx_messages_conversation is partial on
-- is_deleted = FALSE and cannot answer a read that returns deleted rows.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_messages_conversation_created_id
    ON messages (conversation_id, created_at DESC, id DESC);

-- Serves the reactions of one message in time order. The UNIQUE (message_id, user_id) constraint
-- already finds a message's reactions, but it cannot return them ordered by reacted_at.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_message_reactions_message
    ON message_reactions (message_id, reacted_at DESC);

-- Serves the conversation list's latest-reaction read, which starts from reactions newer than a
-- conversation's last message.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_message_reactions_reacted_at
    ON message_reactions (reacted_at DESC);
