-- Gives comments and stories an administrative tombstone that is orthogonal to the owner's own,
-- following V77, which did the same for messages.
--
-- Both tables carry a single deleted_at today, written both by the owner deleting their own content
-- and by an administrator removing it. The two are indistinguishable once written, so an
-- administrative restore clears a column the owner may have set and returns content its author had
-- already deleted. posts avoided this with status_before_moderation in V59 and messages with
-- admin_removed_at in V77; these are the last two tables still conflating the two intents.
--
-- admin_removed_at is therefore a second, independent tombstone. Removal sets it and leaves
-- deleted_at exactly as it was; restore clears it and never touches deleted_at. A row is hidden
-- when either tombstone is set.
--
-- No backfill, and none is possible. Every existing row gets NULL, which is correct for every row
-- an owner deleted and wrong for every row an administrator removed before this migration: those
-- keep their deleted_at and stay hidden, but they now read as owner deletions and an administrative
-- restore will no longer return them. The admin_actions audit log records which rows those were, but
-- reconstructing the mapping would mean guessing at rows whose deleted_at may since have been
-- rewritten by an owner action, so it is deliberately not attempted.

ALTER TABLE comments ADD COLUMN admin_removed_at TIMESTAMPTZ;
ALTER TABLE stories ADD COLUMN admin_removed_at TIMESTAMPTZ;

COMMENT ON COLUMN comments.admin_removed_at IS
    'Set by administrative removal; independent of the author-owned deleted_at. A comment is hidden when either column is set, and clearing this one never undoes the author''s own deletion.';

COMMENT ON COLUMN stories.admin_removed_at IS
    'Set by administrative removal; independent of the owner-owned deleted_at. A story is hidden when either column is set, and expires_at continues to decide visibility independently of both.';

-- No index on either column, deliberately. Both are NULL for effectively every row, so a plain
-- index is unselective for the IS NULL predicate every read adds, and the reads are already served
-- by the existing composite indexes on their leading columns (idx_comments_post_created,
-- idx_comments_post_top_liked, idx_stories_expires). A partial index WHERE admin_removed_at IS NOT
-- NULL would serve only an administrative audit listing, which no query performs today.

-- The two comment counters below transition on deleted_at. Leaving them alone would make
-- posts.comment_count and comments.reply_count count administratively removed comments, because
-- removal no longer writes the column they watch. Both are redefined to transition on the combined
-- visibility predicate instead. GLOBAL_RULES section 2 makes these counters trigger-owned, so this
-- correction belongs here and not in application code.

CREATE OR REPLACE FUNCTION fn_post_comment_count()
RETURNS TRIGGER AS $$
DECLARE
    was_visible BOOLEAN;
    now_visible BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL THEN
            UPDATE posts SET comment_count = comment_count + 1 WHERE id = NEW.post_id;
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL THEN
            UPDATE posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = OLD.post_id;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        was_visible := OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL;
        now_visible := NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL;
        IF was_visible AND NOT now_visible THEN
            UPDATE posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = NEW.post_id;
        ELSIF now_visible AND NOT was_visible THEN
            UPDATE posts SET comment_count = comment_count + 1 WHERE id = NEW.post_id;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_comment_reply_count()
RETURNS TRIGGER AS $$
DECLARE
    was_visible BOOLEAN;
    now_visible BOOLEAN;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.parent_id IS NOT NULL THEN
        IF NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL THEN
            UPDATE comments SET reply_count = reply_count + 1 WHERE id = NEW.parent_id;
        END IF;
    ELSIF TG_OP = 'DELETE' AND OLD.parent_id IS NOT NULL THEN
        IF OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL THEN
            UPDATE comments SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = OLD.parent_id;
        END IF;
    ELSIF TG_OP = 'UPDATE' AND NEW.parent_id IS NOT NULL THEN
        was_visible := OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL;
        now_visible := NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL;
        IF was_visible AND NOT now_visible THEN
            UPDATE comments SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = NEW.parent_id;
        ELSIF now_visible AND NOT was_visible THEN
            UPDATE comments SET reply_count = reply_count + 1 WHERE id = NEW.parent_id;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- active_stories filters deleted_at only, so it would serve administratively removed stories.
-- Dropped rather than replaced: the view selects s.*, and adding a column to stories moves
-- username and avatar_url along by one position, which CREATE OR REPLACE VIEW refuses. Nothing in
-- the application reads this view; it is a convenience view declared in V17 and in the reference
-- schema only.
DROP VIEW IF EXISTS active_stories;

CREATE VIEW active_stories AS
SELECT s.*, u.username, u.avatar_url
FROM stories s
JOIN users u ON u.id = s.user_id AND u.deleted_at IS NULL
WHERE s.deleted_at IS NULL
  AND s.admin_removed_at IS NULL
  AND s.expires_at > NOW();
