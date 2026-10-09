-- Adds the enum values the interaction control phase needs.
--
-- The three hidden word actions audit the administrator-managed platform-wide word list. The two
-- reaction actions, the message_reaction report type and the reaction_removed notification type
-- serve the moderation of message reactions, which lands with the reactions feature itself.
--
-- This file contains nothing but ALTER TYPE statements. PostgreSQL does not make a new enum value
-- usable until the adding transaction has committed, so the config table rows that name these
-- values live in V142.

ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'create_hidden_word';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'edit_hidden_word';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'delete_hidden_word';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'remove_message_reaction';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'restore_message_reaction';
ALTER TYPE report_type ADD VALUE IF NOT EXISTS 'message_reaction';
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'reaction_removed';
