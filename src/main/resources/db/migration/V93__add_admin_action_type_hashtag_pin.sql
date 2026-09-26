-- Adds the audit types for pinning and unpinning a hashtag.
--
-- Two values rather than one reversible "pin" action, matching how ban_hashtag and unban_hashtag
-- are already modelled: an audit row has to say which direction the administrator moved the state,
-- and a single value with the direction buried in metadata is not readable as an audit trail.
--
-- This file contains nothing but ALTER TYPE. The moderation_action_configs rows naming these values
-- live in V94, because PostgreSQL does not make a new enum value usable until the adding
-- transaction has committed.

ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'pin_hashtag';
ALTER TYPE admin_action_type ADD VALUE IF NOT EXISTS 'unpin_hashtag';
