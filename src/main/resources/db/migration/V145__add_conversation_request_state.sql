-- Message requests: a conversation started by someone the recipient does not follow is a
-- request until the recipient accepts it. Every existing conversation takes the default
-- 'accepted', so no row is rewritten and no history changes behaviour.
CREATE TYPE conversation_request_status AS ENUM ('accepted', 'pending', 'declined');

ALTER TABLE conversations
    ADD COLUMN request_status        conversation_request_status NOT NULL DEFAULT 'accepted',
    ADD COLUMN request_recipient_id  UUID NULL REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN request_hidden        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN request_resolved_at   TIMESTAMPTZ NULL;

-- Acceptance keeps request_recipient_id as history, so the first check only binds a request
-- that is still open or was declined.
ALTER TABLE conversations ADD CONSTRAINT chk_conversations_request_recipient
    CHECK (request_status = 'accepted' OR request_recipient_id IS NOT NULL);

-- The hidden flag files a pending request in the recipient's hidden requests folder, so it
-- has no meaning on an accepted conversation.
ALTER TABLE conversations ADD CONSTRAINT chk_conversations_request_hidden
    CHECK (NOT request_hidden OR request_status <> 'accepted');
