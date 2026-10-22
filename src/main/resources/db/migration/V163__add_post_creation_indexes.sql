-- Supporting indexes for the post creation reads. Each is built CONCURRENTLY so the build never
-- blocks writes to a table that is already in use.
--
-- The V34 and V15 created_at indexes stay in place. Admin content listings and a few maintenance
-- reads still order by created_at, and dropping them is a separate change once EXPLAIN shows no
-- reader left (swap the index, then drop the old one, as V107 did).

-- Profile grid and Following feed, ordered by publication time with the id as the tiebreaker.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_posts_user_published_id
    ON posts (user_id, published_at DESC, id DESC)
    WHERE status = 'published' AND deleted_at IS NULL;

-- Hashtag pages, For You hydration and the search index backfill, ordered the same way.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_posts_published_id
    ON posts (published_at DESC, id DESC)
    WHERE status = 'published' AND deleted_at IS NULL;

-- The scheduled publisher's claim query: due posts, oldest first.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_posts_scheduled_due
    ON posts (scheduled_at, id)
    WHERE status = 'scheduled' AND deleted_at IS NULL;

-- The owner's drafts, scheduled posts and archive, most recently changed first.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_posts_owner_unpublished
    ON posts (user_id, status, updated_at DESC, id DESC)
    WHERE status IN ('draft', 'scheduled', 'archived') AND deleted_at IS NULL;

-- The location page, and the foreign key from posts to places.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_posts_place_published
    ON posts (place_id, published_at DESC, id DESC)
    WHERE status = 'published' AND deleted_at IS NULL AND place_id IS NOT NULL;

-- A co-author's grid and the Following feed, which look posts up by an accepted co-author.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_collaborators_user_accepted
    ON post_collaborators (user_id, post_id)
    WHERE status = 'accepted';

-- The invitations a person has not answered yet.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_collaborators_user_invited
    ON post_collaborators (user_id, invited_at DESC)
    WHERE status = 'invited';

-- Covers the ON DELETE SET NULL foreign key, so deleting an account does not scan the table.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_collaborators_invited_by
    ON post_collaborators (invited_by)
    WHERE invited_by IS NOT NULL;

-- The Tagged tab: approved tags of one person.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_user_tags_user_approved
    ON post_user_tags (tagged_user_id, post_id)
    WHERE status = 'approved';

-- The tag requests a person has not answered yet.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_user_tags_user_pending
    ON post_user_tags (tagged_user_id, created_at DESC)
    WHERE status = 'pending';

-- Covers the ON DELETE SET NULL foreign key on the tagger.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_user_tags_created_by
    ON post_user_tags (created_by)
    WHERE created_by IS NOT NULL;

-- Cover the ON DELETE CASCADE foreign keys from the mentioned account.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_mentions_user
    ON post_mentions (mentioned_user_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_comment_mentions_user
    ON comment_mentions (mentioned_user_id);

-- Trigram search over place names, the PostgreSQL fallback behind the places index.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_places_normalized_name_trgm
    ON places USING gin (normalized_name gin_trgm_ops);

-- The administrative registry, by status and then newest first, and the foreign keys to users.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_places_status_created
    ON places (status, created_at DESC, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_places_created_by
    ON places (created_by)
    WHERE created_by IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_places_status_by
    ON places (status_by)
    WHERE status_by IS NOT NULL;
