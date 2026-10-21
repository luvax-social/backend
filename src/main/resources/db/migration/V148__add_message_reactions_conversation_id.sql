-- Denormalises the owning conversation onto each reaction. The conversation list reads the newest
-- visible reaction of every listed conversation, and without this column it could only reach a
-- conversation's reactions by walking every reaction newer than that conversation's last message
-- and then filtering through messages, so the cost followed total reaction volume on the platform
-- instead of the listed conversations. The column is copied from the reacted-to message when the
-- reaction is inserted and never changes, because a message never moves between conversations.
-- The supporting index is built CONCURRENTLY in a later migration.
ALTER TABLE message_reactions ADD COLUMN conversation_id UUID;

UPDATE message_reactions r
SET conversation_id = m.conversation_id
FROM messages m
WHERE m.id = r.message_id;

ALTER TABLE message_reactions ALTER COLUMN conversation_id SET NOT NULL;

ALTER TABLE message_reactions
    ADD CONSTRAINT fk_message_reactions_conversation
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE;
