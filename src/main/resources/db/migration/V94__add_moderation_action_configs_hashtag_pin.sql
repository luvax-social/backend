-- Registers display metadata for the admin_action_type values added by V93.
--
-- An enum value without a config row breaks the vocabulary surface, which reads this table to
-- render the moderation action list, so the pair is added together exactly as V54/V55, V75/V76 and
-- V79/V80 did.
--
-- requires_reason is FALSE for both. Pinning grants prominence rather than taking a capability
-- away, so it does not meet the bar V18 set for a mandatory reason; the note field stays optional.
-- Both are reversible: the opposite action restores the previous state exactly.

INSERT INTO moderation_action_configs (action_key, display_name, requires_reason, is_reversible, is_enabled) VALUES
    ('pin_hashtag', 'Pin Hashtag', FALSE, TRUE, TRUE),
    ('unpin_hashtag', 'Unpin Hashtag', FALSE, TRUE, TRUE)
ON CONFLICT (action_key) DO NOTHING;
