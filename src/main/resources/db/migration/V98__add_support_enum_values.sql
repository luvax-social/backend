-- Audit and notification enum values for the support centre.
--
-- This file contains nothing but ALTER TYPE, following V79 and V60. PostgreSQL does not make a new
-- enum value usable until the adding transaction has committed, so the moderation_action_configs
-- and notification_type_configs rows naming these values live in V99.
--
-- Three action values, which is the minimum that keeps the audit log readable. Claiming a ticket is
-- deliberately not audited: it is a queue mechanic rather than a decision about a person, and an
-- audit row for every claim would bury the rows that record verdicts.

ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'respond_support_ticket';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'reject_support_ticket';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'escalate_support_ticket';

-- One notification value. A ticket reaching a terminal state is the only transition worth telling
-- the user about in-product; the mail carries the actual response.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'support_ticket_update';
