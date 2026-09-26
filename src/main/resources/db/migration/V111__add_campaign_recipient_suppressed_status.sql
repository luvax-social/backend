-- A per-recipient outcome for a recipient the deployment was configured never to mail.
--
-- The campaign job previously recorded this as FAILED, which reports an operator's own
-- configuration choice as a delivery failure and makes a campaign summary read as though sends
-- broke when nothing was ever attempted. SKIPPED_OPTED_OUT is not the same thing and must not be
-- reused for it: that value records the recipient's decision, this one records the deployment's.
--
-- ALTER TYPE only. Following V98, V79 and V60: PostgreSQL does not make a new enum value usable
-- until the adding transaction has committed, so nothing here may write a row using it.
--
-- Adds a value; rewrites and deletes no rows, so no archive table is required.

ALTER TYPE mail_campaign_recipient_status ADD VALUE IF NOT EXISTS 'skipped_not_allowed';
