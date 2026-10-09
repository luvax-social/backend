-- Registers display metadata for the values added by V141.
--
-- Separate from V141 because these INSERTs and the report_reason_configs update name the new enum
-- values, which PostgreSQL does not make usable until the ALTER TYPE transaction has committed.
--
-- The three hidden word actions demand a reason, because a word list change decides what a whole
-- audience is shown. Deleting a word is not reversible: the row is configuration, not user content,
-- and only the audit metadata keeps the phrase. Creating or editing one can be undone by the
-- opposite edit. Removing a message reaction demands a reason and restoring one does not, the same
-- shape remove_message and restore_message follow in V76.

INSERT INTO moderation_action_configs (action_key, display_name, requires_reason, is_reversible, is_enabled) VALUES
    ('create_hidden_word',       'Create Hidden Word',       TRUE,  TRUE,  TRUE),
    ('edit_hidden_word',         'Edit Hidden Word',         TRUE,  TRUE,  TRUE),
    ('delete_hidden_word',       'Delete Hidden Word',       TRUE,  FALSE, TRUE),
    ('remove_message_reaction',  'Remove Message Reaction',  TRUE,  TRUE,  TRUE),
    ('restore_message_reaction', 'Restore Message Reaction', FALSE, TRUE,  TRUE)
ON CONFLICT (action_key) DO NOTHING;

-- Not user-toggleable, following comment_removed in V113: an enforcement notice is not a preference,
-- and an account that could switch it off would have its reaction removed and never be told.
INSERT INTO notification_type_configs (type_key, display_name, template_key, is_user_toggleable, is_enabled) VALUES
    ('reaction_removed', 'Reaction Removed', 'reaction_removed', FALSE, TRUE)
ON CONFLICT (type_key) DO NOTHING;

-- The reasons that already list their report types gain message_reaction. The reasons with an
-- empty array apply to every report type, so they already cover it.
UPDATE report_reason_configs
SET applies_to = array_append(applies_to, 'message_reaction')
WHERE reason_key IN ('nudity', 'violence', 'hate_speech')
  AND NOT ('message_reaction' = ANY (applies_to));
