-- Registers the three content-removal notification types added by V112.
--
-- is_user_toggleable is FALSE for all three, following the post_removed row in V84 and the
-- warning and support_ticket_update rows before it. An enforcement notice is not a preference:
-- an account that could switch it off would have its content removed and never be told.

INSERT INTO notification_type_configs (type_key, display_name, template_key, is_user_toggleable, is_enabled) VALUES
    ('comment_removed', 'Comment Removed', 'comment_removed', FALSE, TRUE),
    ('story_removed', 'Story Removed', 'story_removed', FALSE, TRUE),
    ('message_removed', 'Message Removed', 'message_removed', FALSE, TRUE)
ON CONFLICT (type_key) DO NOTHING;
