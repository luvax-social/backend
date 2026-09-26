-- Enum values for verification, added ahead of every table and config row that names them.
--
-- verification_request joins support_category rather than becoming a category system of its own,
-- because the support framework already owns the queue, the guarded claim, the conflict-of-interest
-- rule, the audit trail and the outbox mail path. A second review queue would be a second place for
-- a request to be forgotten.
--
-- Three admin_action_type values rather than one reversible "verification" action, matching how
-- ban_hashtag and unban_hashtag are already modelled: an audit row has to say which way the state
-- moved. reject_verification is separate from revoke_verification because they are different
-- events - one refuses a grant that never existed, the other withdraws one that did - and a
-- moderator reading the log needs to tell them apart.
--
-- This file contains nothing but ALTER TYPE. Every config row naming these values lives in V105,
-- because PostgreSQL does not make a new enum value usable until the adding transaction has
-- committed. Same split as V98/V99, V93/V94 and V79/V80.

ALTER TYPE support_category ADD VALUE IF NOT EXISTS 'verification_request';

ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'grant_verification';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'reject_verification';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'revoke_verification';
