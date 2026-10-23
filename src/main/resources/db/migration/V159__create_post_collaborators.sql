-- Co-authorship of a post.
--
-- The post owner stays the only account that can edit or delete it; a co-author is invited by the
-- owner, appears on the post only after accepting, and can leave. Rows are kept after a response
-- (declined, left, removed) rather than deleted, so accepted_at survives for attributing a post's
-- performance to every author who held a share of it.
--
-- status:
--   invited    the owner asked; the invitee has not answered and the post is not shown to them yet
--   accepted   the invitee is a co-author
--   declined   the invitee refused
--   left       an accepted co-author left
--   removed    the owner withdrew an invitation or removed a co-author
--
-- Two triggers keep users.post_count equal to the posts a user owns or co-authors that are
-- published and not deleted:
--   trg_post_collaborator_count        a co-author joins or leaves a published post
--   trg_post_authors_count (on posts)  a post becomes or stops being published or deleted
-- trg_post_authors_count is named so it fires before trg_post_count. Both lock user rows, and
-- taking the owner and every co-author in id order first, in the earlier trigger, is what keeps two
-- posts that swap owner and co-author from deadlocking each other.
--
-- A third trigger, trg_post_collaborators_limit, caps live rows (invited plus accepted) at the
-- max_post_collaborators setting, three by default, under a lock on the post row so concurrent
-- invitations cannot exceed it, and refuses the owner as a co-author of their own post.
--
-- A hard delete of a post (administrative only) is handled by a BEFORE DELETE trigger, because the
-- cascade to this table would otherwise remove the rows before the count could be adjusted.

CREATE TYPE collaboration_status AS ENUM ('invited', 'accepted', 'declined', 'left', 'removed');

CREATE TABLE post_collaborators (
    post_id         UUID                    NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    user_id         UUID                    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status          collaboration_status    NOT NULL DEFAULT 'invited',
    invited_by      UUID                    REFERENCES users(id) ON DELETE SET NULL,
    invited_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    responded_at    TIMESTAMPTZ,
    accepted_at     TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    PRIMARY KEY (post_id, user_id)
);

CREATE TRIGGER trg_post_collaborators_updated_at
    BEFORE UPDATE ON post_collaborators
    FOR EACH ROW EXECUTE FUNCTION fn_update_updated_at();

CREATE VIEW v_post_authors AS
    SELECT id AS post_id, user_id FROM posts
    UNION ALL
    SELECT post_id, user_id FROM post_collaborators WHERE status = 'accepted';

CREATE FUNCTION fn_post_collaborators_limit()
RETURNS TRIGGER AS $$
DECLARE
    owner_id    UUID;
    max_live    INTEGER;
    live_rows   INTEGER;
BEGIN
    IF NEW.status NOT IN ('invited', 'accepted') THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status IN ('invited', 'accepted') THEN
        RETURN NEW;
    END IF;
    SELECT user_id INTO owner_id FROM posts WHERE id = NEW.post_id FOR UPDATE;
    IF owner_id = NEW.user_id THEN
        RAISE EXCEPTION 'the owner of post % cannot be its co-author', NEW.post_id
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE((SELECT value::INTEGER FROM system_settings WHERE key = 'max_post_collaborators'), 3)
        INTO max_live;
    SELECT count(*) INTO live_rows
    FROM post_collaborators
    WHERE post_id = NEW.post_id
      AND status IN ('invited', 'accepted')
      AND user_id <> NEW.user_id;
    IF live_rows >= max_live THEN
        RAISE EXCEPTION 'post % already has % co-authors', NEW.post_id, live_rows
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_post_collaborators_limit
    BEFORE INSERT OR UPDATE OF status ON post_collaborators
    FOR EACH ROW EXECUTE FUNCTION fn_post_collaborators_limit();

CREATE FUNCTION fn_post_collaborator_count()
RETURNS TRIGGER AS $$
DECLARE
    post_live   BOOLEAN;
    was_accepted BOOLEAN := FALSE;
    is_accepted  BOOLEAN := FALSE;
    target_user  UUID;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        was_accepted := OLD.status = 'accepted';
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        is_accepted := NEW.status = 'accepted';
    END IF;
    IF was_accepted = is_accepted THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    target_user := COALESCE(NEW.user_id, OLD.user_id);
    SELECT (status = 'published' AND deleted_at IS NULL) INTO post_live
    FROM posts WHERE id = COALESCE(NEW.post_id, OLD.post_id);
    IF post_live IS TRUE THEN
        IF is_accepted THEN
            UPDATE users SET post_count = post_count + 1 WHERE id = target_user;
        ELSE
            UPDATE users SET post_count = GREATEST(post_count - 1, 0) WHERE id = target_user;
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_post_collaborator_count
    AFTER INSERT OR UPDATE OR DELETE ON post_collaborators
    FOR EACH ROW EXECUTE FUNCTION fn_post_collaborator_count();

CREATE FUNCTION fn_post_authors_count()
RETURNS TRIGGER AS $$
DECLARE
    old_live    BOOLEAN := FALSE;
    new_live    BOOLEAN := FALSE;
    locked_ids  UUID[];
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_live := OLD.status = 'published' AND OLD.deleted_at IS NULL;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        new_live := NEW.status = 'published' AND NEW.deleted_at IS NULL;
    END IF;
    IF old_live = new_live THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    SELECT array_agg(user_id ORDER BY user_id) INTO locked_ids
    FROM (
        SELECT COALESCE(NEW.user_id, OLD.user_id) AS user_id
        UNION
        SELECT user_id FROM post_collaborators
        WHERE post_id = COALESCE(NEW.id, OLD.id) AND status = 'accepted'
    ) authors;
    PERFORM id FROM users WHERE id = ANY (locked_ids) ORDER BY id FOR UPDATE;
    IF new_live THEN
        UPDATE users SET post_count = post_count + 1
        WHERE id IN (SELECT user_id FROM post_collaborators
                     WHERE post_id = NEW.id AND status = 'accepted');
    ELSE
        UPDATE users SET post_count = GREATEST(post_count - 1, 0)
        WHERE id IN (SELECT user_id FROM post_collaborators
                     WHERE post_id = OLD.id AND status = 'accepted');
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_post_authors_count
    AFTER UPDATE ON posts
    FOR EACH ROW EXECUTE FUNCTION fn_post_authors_count();

CREATE TRIGGER trg_post_authors_count_on_delete
    BEFORE DELETE ON posts
    FOR EACH ROW EXECUTE FUNCTION fn_post_authors_count();
