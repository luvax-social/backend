-- The affinity blend's join key, built CONCURRENTLY.
--
-- V90 created this table with only its composite primary key and V91 added one covering index;
-- both lead on user_id, which serves the two read paths the affinity work was designed for
-- (findTopForUser, countForUser). The suggestion blend then added a third consumer,
-- findAffinityCandidates, which joins the table to itself on hashtag_id - the one access pattern
-- neither existing index supports. That side of the join therefore sequentially scanned the whole
-- table once per viewer per precompute cycle, an O(viewers x table size) shape on a table sized
-- users x hashtags.
--
-- score is INCLUDEd because LEAST(a1.score, a2.score) is the only other column the join reads from
-- the a2 side, so the scan stays index-only rather than returning to the heap 12k times per viewer.
--
-- Non-transactional via its .sql.conf sidecar, as every other index migration in this tree is.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_hashtag_affinity_hashtag_user
    ON user_hashtag_affinity (hashtag_id, user_id) INCLUDE (score);
