-- Registers display metadata for the admin_action_type value added by V101.
--
-- Separate from V101 because this INSERT names the new enum value, which PostgreSQL does not make
-- usable until the ALTER TYPE transaction has committed. Same split as V98/V99 and V79/V80.
--
-- requires_reason is FALSE: the campaign row already records the subject, the body snapshot and
-- every recipient, so a free-text reason would add nothing a reviewer cannot already see.
-- is_reversible is FALSE: mail that has been handed to the provider cannot be recalled.

INSERT INTO moderation_action_configs (action_key, display_name, requires_reason, is_reversible, is_enabled) VALUES
    ('send_mail_campaign', 'Send Mail Campaign', FALSE, FALSE, TRUE)
ON CONFLICT (action_key) DO NOTHING;
