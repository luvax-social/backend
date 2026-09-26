-- Platform-wide pin, so an administrator can hold a hashtag at the top of the trending list.
--
-- Two columns rather than a boolean: who pinned it is an accountability fact of the same kind the
-- status_by column already records, and a boolean alone leaves the audit log as the only place
-- that answer exists.
--
-- pinned_at doubles as the flag. A pin is either absent or has a time, exactly as the deleted_at
-- and admin_removed_at tombstones elsewhere in this schema work, so there is no way to hold a
-- pinned_by without a pinned_at or the reverse.
--
-- ON DELETE SET NULL on pinned_by matches status_by (V67): removing an administrator's account
-- must not cascade into deleting hashtags, and losing the attribution is the acceptable outcome.
--
-- No CREATE INDEX here. Pinned hashtags are read only as part of building the trending list, which
-- already reads every hashtag_trending row for the current period and is bounded by that; a
-- partial index on a column that will hold a handful of non-null values across the table would not
-- change that plan. The index migration this run does add, V91, is a real CREATE INDEX and builds
-- CONCURRENTLY.

ALTER TABLE hashtags
    ADD COLUMN pinned_at TIMESTAMPTZ,
    ADD COLUMN pinned_by UUID REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE hashtags
    ADD CONSTRAINT hashtags_pin_pair CHECK (
        (pinned_at IS NULL AND pinned_by IS NULL)
        OR (pinned_at IS NOT NULL)
    );

COMMENT ON COLUMN hashtags.pinned_at IS
    'When an administrator pinned this hashtag platform-wide; NULL means not pinned.';
