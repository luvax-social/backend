-- Serves the only read shape this table has: the caller's top hashtags by score, descending.
--
-- Runs outside a transaction, declared in the accompanying .sql.conf, so the build is
-- CONCURRENTLY.
--
-- The primary key (user_id, hashtag_id) already narrows to one user, but it carries no ordering on
-- score, so serving "top N for this user" through it means reading every row that user has and
-- sorting. This index makes the same read an index scan that stops after N.
--
-- hashtag_id is included as the trailing column so the ordering is total. Two hashtags with an
-- identical score are otherwise returned in an unspecified order that can differ between calls,
-- which is the same keyset defect the (created_at, id) tiebreakers elsewhere in this schema exist
-- to prevent: a page boundary landing inside a group of equal scores can drop or repeat rows.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_hashtag_affinity_user_score
    ON user_hashtag_affinity (user_id, score DESC, hashtag_id DESC);
