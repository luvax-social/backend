-- Every index for the verification and suggestion work, built CONCURRENTLY.
--
-- Separate from V106 and V108, and non-transactional via its .sql.conf sidecar, because
-- CREATE INDEX CONCURRENTLY cannot run inside a transaction block. Same split every other index
-- migration in this tree uses.

-- One active grant per account. This is the real guard: the service checks first so the ordinary
-- case answers a named error code, but a concurrent double grant is refused here.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_user_verifications_active
    ON user_verifications (user_id)
    WHERE revoked_at IS NULL;

-- The resubmission read: every grant this account has ever held, newest first, so a moderator
-- reviewing a new request sees what was granted before and why it was withdrawn.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_verifications_user_granted
    ON user_verifications (user_id, granted_at DESC);

-- The cold-start read: verified accounts ordered by follower count within a category. Partial on
-- is_verified because the overwhelming majority of rows are not verified and never will be, so the
-- index stays a small fraction of the table.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_verified_followers
    ON users (verified_category, follower_count DESC)
    WHERE is_verified = TRUE AND deleted_at IS NULL;

-- The suggestion read: one viewer's list in rank order. The primary key already leads on user_id,
-- but it orders by suggested_id, so reading in rank order would sort. This serves the read directly.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_suggestions_user_rank
    ON user_suggestions (user_id, rank);

-- The verification queue join: a moderator opening a ticket reads the request beside it, and the
-- resubmission history reads every request this account has made.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_verification_requests_user_created
    ON verification_requests (user_id, created_at DESC);
