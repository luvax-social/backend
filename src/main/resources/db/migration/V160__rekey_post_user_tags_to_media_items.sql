-- Re-keys people tags on the stable post_media item instead of the post and the asset.
--
-- Before this migration a tag was keyed (post_id, tagged_user_id), so a person could be tagged once
-- per post whatever its length, and it pointed at a media asset, which does not prove the asset
-- belongs to the post. A tag now belongs to one post_media row, the same person can be tagged on
-- several items of a carousel, an item holds at most max_post_tags_per_media tags, and the
-- coordinates are fractions of the item between 0 and 1.
--
-- No code wrote this table before this release, but every existing row is first copied, unchanged,
-- into archived_post_user_tags. Rows are then mapped to the lowest-position post_media row of the
-- same post that uses the tag's asset; rows that cannot be mapped, or whose coordinates are missing
-- or outside 0 to 1, are removed from the live table and remain only in the archive. Mapped rows
-- become approved, because nobody was ever asked about them.
--
-- status:
--   pending    the tagged person has not approved it; only the post owner and that person see it
--   approved   shown to everyone who can see the post and on the tagged person's profile
--
-- The limit trigger takes a lock on the post_media row, which the foreign key check of a concurrent
-- insert also needs, so two inserts into one item cannot both pass the count.

CREATE TYPE tag_status AS ENUM ('pending', 'approved');

CREATE TABLE archived_post_user_tags AS TABLE post_user_tags;
ALTER TABLE archived_post_user_tags ADD COLUMN archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Lets a tag reference (post_id, post_media_id) so a tag can never name an item of another post.
ALTER TABLE post_media ADD CONSTRAINT uq_post_media_post_id_id UNIQUE (post_id, id);

ALTER TABLE post_user_tags DROP CONSTRAINT post_user_tags_pkey;

ALTER TABLE post_user_tags ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE post_user_tags ADD COLUMN post_media_id UUID;
ALTER TABLE post_user_tags ADD COLUMN status tag_status NOT NULL DEFAULT 'approved';
ALTER TABLE post_user_tags ADD COLUMN created_by UUID REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE post_user_tags ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE post_user_tags ADD COLUMN approved_at TIMESTAMPTZ;

UPDATE post_user_tags t
SET post_media_id = (
    SELECT pm.id
    FROM post_media pm
    WHERE pm.post_id = t.post_id AND pm.media_asset_id = t.media_asset_id
    ORDER BY pm.position
    LIMIT 1);

DELETE FROM post_user_tags
WHERE post_media_id IS NULL
   OR x_position IS NULL
   OR y_position IS NULL
   OR x_position NOT BETWEEN 0 AND 1
   OR y_position NOT BETWEEN 0 AND 1;

UPDATE post_user_tags SET approved_at = created_at;

ALTER TABLE post_user_tags ALTER COLUMN post_media_id SET NOT NULL;
ALTER TABLE post_user_tags DROP COLUMN media_asset_id;
ALTER TABLE post_user_tags ALTER COLUMN x_position TYPE NUMERIC(5,4);
ALTER TABLE post_user_tags ALTER COLUMN y_position TYPE NUMERIC(5,4);
ALTER TABLE post_user_tags ALTER COLUMN x_position SET NOT NULL;
ALTER TABLE post_user_tags ALTER COLUMN y_position SET NOT NULL;

ALTER TABLE post_user_tags ADD PRIMARY KEY (id);
ALTER TABLE post_user_tags
    ADD CONSTRAINT uq_post_user_tags_media_user UNIQUE (post_media_id, tagged_user_id);
ALTER TABLE post_user_tags
    ADD CONSTRAINT fk_post_user_tags_media
    FOREIGN KEY (post_id, post_media_id) REFERENCES post_media (post_id, id) ON DELETE CASCADE;
ALTER TABLE post_user_tags
    ADD CONSTRAINT chk_post_user_tags_x_position CHECK (x_position BETWEEN 0 AND 1);
ALTER TABLE post_user_tags
    ADD CONSTRAINT chk_post_user_tags_y_position CHECK (y_position BETWEEN 0 AND 1);

CREATE FUNCTION fn_post_user_tag_limit()
RETURNS TRIGGER AS $$
DECLARE
    max_tags     INTEGER;
    current_tags INTEGER;
BEGIN
    PERFORM 1 FROM post_media WHERE id = NEW.post_media_id FOR UPDATE;
    SELECT COALESCE((SELECT value::INTEGER FROM system_settings WHERE key = 'max_post_tags_per_media'), 20)
        INTO max_tags;
    SELECT count(*) INTO current_tags FROM post_user_tags WHERE post_media_id = NEW.post_media_id;
    IF current_tags >= max_tags THEN
        RAISE EXCEPTION 'post media % already has % tags', NEW.post_media_id, current_tags
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_post_user_tags_limit
    BEFORE INSERT ON post_user_tags
    FOR EACH ROW EXECUTE FUNCTION fn_post_user_tag_limit();
