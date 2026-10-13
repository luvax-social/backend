-- A comment hidden by its post owner's Hidden Words or interaction limit is persisted but shown to
-- nobody except its author, so the two comment counters must not count it; unhiding it counts it.
--
-- moderation_status is a free VARCHAR that has only ever held 'approved'. The CHECK below ties the
-- new 'hidden' state to a recorded reason, and fails the migration on any row that is not approved,
-- so the guard checks that first and names the problem instead of leaving a bare constraint error.
DO $$
DECLARE
    unapproved BIGINT;
BEGIN
    SELECT count(*) INTO unapproved FROM comments WHERE moderation_status <> 'approved';
    IF unapproved <> 0 THEN
        RAISE EXCEPTION 'V143 cannot add chk_comments_hidden_reason: % comments have a moderation_status other than approved; resolve them before migrating', unapproved;
    END IF;
END $$;

ALTER TABLE comments ADD COLUMN hidden_reason VARCHAR(30) NULL;

ALTER TABLE comments ADD CONSTRAINT chk_comments_hidden_reason
    CHECK ((moderation_status = 'hidden') = (hidden_reason IS NOT NULL));

COMMENT ON COLUMN comments.hidden_reason IS
    'Why the comment is hidden: hidden_words or limited_interaction. Set exactly when moderation_status is hidden; independent of the author-owned deleted_at and the administrative admin_removed_at.';

-- Both counters count a comment only while it is approved and carries neither tombstone. The
-- admin_removed_at handling is V95's, unchanged; moderation_status = 'approved' is added to it.
-- The branches never read OLD on INSERT or NEW on DELETE, which plpgsql rejects.
CREATE OR REPLACE FUNCTION fn_post_comment_count()
RETURNS TRIGGER AS $$
DECLARE
    old_counted BOOLEAN := FALSE;
    new_counted BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        new_counted := NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL
                       AND NEW.moderation_status = 'approved';
        IF new_counted THEN
            UPDATE posts SET comment_count = comment_count + 1 WHERE id = NEW.post_id;
        END IF;
    ELSIF TG_OP = 'DELETE' THEN
        old_counted := OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL
                       AND OLD.moderation_status = 'approved';
        IF old_counted THEN
            UPDATE posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = OLD.post_id;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        old_counted := OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL
                       AND OLD.moderation_status = 'approved';
        new_counted := NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL
                       AND NEW.moderation_status = 'approved';
        IF old_counted AND NOT new_counted THEN
            UPDATE posts SET comment_count = GREATEST(comment_count - 1, 0) WHERE id = NEW.post_id;
        ELSIF new_counted AND NOT old_counted THEN
            UPDATE posts SET comment_count = comment_count + 1 WHERE id = NEW.post_id;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fn_comment_reply_count()
RETURNS TRIGGER AS $$
DECLARE
    old_counted BOOLEAN := FALSE;
    new_counted BOOLEAN := FALSE;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.parent_id IS NOT NULL THEN
        new_counted := NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL
                       AND NEW.moderation_status = 'approved';
        IF new_counted THEN
            UPDATE comments SET reply_count = reply_count + 1 WHERE id = NEW.parent_id;
        END IF;
    ELSIF TG_OP = 'DELETE' AND OLD.parent_id IS NOT NULL THEN
        old_counted := OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL
                       AND OLD.moderation_status = 'approved';
        IF old_counted THEN
            UPDATE comments SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = OLD.parent_id;
        END IF;
    ELSIF TG_OP = 'UPDATE' AND NEW.parent_id IS NOT NULL THEN
        old_counted := OLD.deleted_at IS NULL AND OLD.admin_removed_at IS NULL
                       AND OLD.moderation_status = 'approved';
        new_counted := NEW.deleted_at IS NULL AND NEW.admin_removed_at IS NULL
                       AND NEW.moderation_status = 'approved';
        IF old_counted AND NOT new_counted THEN
            UPDATE comments SET reply_count = GREATEST(reply_count - 1, 0) WHERE id = NEW.parent_id;
        ELSIF new_counted AND NOT old_counted THEN
            UPDATE comments SET reply_count = reply_count + 1 WHERE id = NEW.parent_id;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;
