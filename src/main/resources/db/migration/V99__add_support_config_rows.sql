-- Registers display metadata for the enum values added by V98.
--
-- Separate from V98 because these INSERTs name the new enum values, which PostgreSQL does not make
-- usable until the ALTER TYPE transaction has committed. This is the same split V60/V61,
-- V54/V55, V75/V76 and V79/V80 all use.

-- requires_reason is TRUE for all three ticket actions. Every one of them is a decision communicated
-- to a person, and the reason column is what a later reviewer reads to understand it. is_reversible
-- is FALSE for all three: a response has been mailed and cannot be unsent, and reopening a ticket is
-- not modelled - the user opens a new one.
INSERT INTO moderation_action_configs (action_key, display_name, requires_reason, is_reversible, is_enabled) VALUES
    ('respond_support_ticket',  'Respond to Support Ticket', TRUE, FALSE, TRUE),
    ('reject_support_ticket',   'Reject Support Ticket',     TRUE, FALSE, TRUE),
    ('escalate_support_ticket', 'Escalate Support Ticket',   TRUE, FALSE, TRUE)
ON CONFLICT (action_key) DO NOTHING;

-- is_user_toggleable is FALSE, for the reason V61 gives for the warning row. A support ticket
-- update is the answer to something the user themselves asked; an account that could switch it off
-- would ask a question and never be told it had been answered.
INSERT INTO notification_type_configs (type_key, display_name, template_key, is_user_toggleable, is_enabled) VALUES
    ('support_ticket_update', 'Support Ticket Update', 'support_ticket_update', FALSE, TRUE)
ON CONFLICT (type_key) DO NOTHING;
