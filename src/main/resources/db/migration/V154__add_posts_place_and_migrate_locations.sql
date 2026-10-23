-- Points posts at the places registry and moves the existing free-form locations into it.
--
-- Every post that carries any of location_name, latitude or longitude is first copied, unchanged,
-- into archived_post_locations. V155 then drops the three columns, so this archive is the only copy
-- of the coordinates, which the registry deliberately does not keep.
--
-- Each distinct non-empty label becomes one place, keyed by fn_normalise_place_name. When several
-- posts spell the same place differently, the label of the earliest post becomes the display name
-- and its author becomes created_by. Names longer than 100 characters are cut to the column width;
-- the join below cuts the key the same way so every post still finds its place.
--
-- The back-fill is not an edit, so it must not move posts.updated_at. The counter trigger is
-- created after the back-fill and the counts are then computed once, instead of being driven row by
-- row through the trigger.
--
-- trg_place_post_count counts a place's published, non-deleted posts. It handles every way a post
-- can enter or leave that set: insert, publication, archive, soft delete, moderation, and moving
-- to another place.

CREATE TABLE archived_post_locations (
    post_id         UUID            PRIMARY KEY,
    location_name   VARCHAR(255),
    latitude        NUMERIC(10,8),
    longitude       NUMERIC(11,8),
    archived_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

INSERT INTO archived_post_locations (post_id, location_name, latitude, longitude)
SELECT id, location_name, latitude, longitude
FROM posts
WHERE location_name IS NOT NULL OR latitude IS NOT NULL OR longitude IS NOT NULL
ON CONFLICT (post_id) DO NOTHING;

ALTER TABLE posts ADD COLUMN place_id UUID REFERENCES places(id);

INSERT INTO places (name, normalized_name, created_by)
SELECT DISTINCT ON (left(fn_normalise_place_name(p.location_name), 100))
       left(btrim(p.location_name), 100),
       left(fn_normalise_place_name(p.location_name), 100),
       p.user_id
FROM posts p
WHERE p.location_name IS NOT NULL
  AND fn_normalise_place_name(p.location_name) <> ''
ORDER BY left(fn_normalise_place_name(p.location_name), 100), p.created_at, p.id
ON CONFLICT (normalized_name) DO NOTHING;

ALTER TABLE posts DISABLE TRIGGER trg_posts_updated_at;

UPDATE posts p
SET place_id = pl.id
FROM places pl
WHERE p.location_name IS NOT NULL
  AND pl.normalized_name = left(fn_normalise_place_name(p.location_name), 100);

ALTER TABLE posts ENABLE TRIGGER trg_posts_updated_at;

UPDATE places pl
SET post_count = c.n
FROM (
    SELECT place_id, count(*) AS n
    FROM posts
    WHERE status = 'published' AND deleted_at IS NULL AND place_id IS NOT NULL
    GROUP BY place_id
) c
WHERE c.place_id = pl.id;

CREATE OR REPLACE FUNCTION fn_place_post_count()
RETURNS TRIGGER AS $$
DECLARE
    old_live BOOLEAN := FALSE;
    new_live BOOLEAN := FALSE;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_live := OLD.place_id IS NOT NULL AND OLD.status = 'published' AND OLD.deleted_at IS NULL;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_live := NEW.place_id IS NOT NULL AND NEW.status = 'published' AND NEW.deleted_at IS NULL;
    END IF;
    IF old_live AND new_live AND OLD.place_id = NEW.place_id THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF old_live THEN
        UPDATE places SET post_count = GREATEST(post_count - 1, 0) WHERE id = OLD.place_id;
    END IF;
    IF new_live THEN
        UPDATE places SET post_count = post_count + 1 WHERE id = NEW.place_id;
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_place_post_count
    AFTER INSERT OR UPDATE OR DELETE ON posts
    FOR EACH ROW EXECUTE FUNCTION fn_place_post_count();
