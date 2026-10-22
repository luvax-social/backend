-- Gives a post a publication time that is separate from its creation time, and a scheduled time.
--
-- published_at is the instant a post first became published. Every ordering that used created_at
-- (the Following feed, profile grids, hashtag and location pages, the search index and the Gorse
-- item timestamp) uses it instead, because a draft or scheduled post can be created long before it
-- is published. It stays NULL until first publication and is never cleared, so archiving and then
-- unarchiving a post returns it to its original place.
--
-- The backfill treats created_at as the publication time of every post that has already been
-- published. Posts an administrator removed keep their prior status in status_before_moderation, so
-- the ones that were published or archived before removal get the same value; without it a
-- moderation restore would bring back a published post with no publication time. A post removed by
-- its owner has no recorded prior status and stays NULL, which is harmless because an owner delete
-- is never reversible.
--
-- fn_posts_published_at is the single writer of the column after the backfill. An explicit value on
-- insert is respected, which is what lets the seed backdate a post; every other path (publish,
-- scheduled publish, moderation restore) gets now() the first time the status becomes published.
--
-- The three constraints are added NOT VALID and validated in V164, so adding them takes no long
-- lock on a large table. NOT VALID still checks every new and changed row.
-- scheduled_at stays set when a scheduled post is removed by moderation, so a restore can tell
-- whether its time has already passed.

ALTER TABLE posts ADD COLUMN published_at TIMESTAMPTZ;
ALTER TABLE posts ADD COLUMN scheduled_at TIMESTAMPTZ;

-- The backfill is not an edit, so it must not move updated_at.
ALTER TABLE posts DISABLE TRIGGER trg_posts_updated_at;

UPDATE posts
SET published_at = created_at
WHERE status IN ('published', 'archived')
   OR (status = 'removed' AND status_before_moderation IN ('published', 'archived'));

ALTER TABLE posts ENABLE TRIGGER trg_posts_updated_at;

CREATE OR REPLACE FUNCTION fn_posts_published_at()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'published' AND NEW.published_at IS NULL THEN
        NEW.published_at := NOW();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_posts_published_at
    BEFORE INSERT OR UPDATE ON posts
    FOR EACH ROW EXECUTE FUNCTION fn_posts_published_at();

ALTER TABLE posts
    ADD CONSTRAINT chk_posts_scheduled_at
    CHECK (status <> 'scheduled' OR scheduled_at IS NOT NULL) NOT VALID;

ALTER TABLE posts
    ADD CONSTRAINT chk_posts_published_at
    CHECK (status NOT IN ('published', 'archived') OR published_at IS NOT NULL) NOT VALID;
