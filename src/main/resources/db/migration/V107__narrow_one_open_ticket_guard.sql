-- Excludes verification requests from the one-open-ticket guard, and gives them their own.
--
-- The guard V100 created is global across every category: one non-terminal ticket per account, of
-- any kind. Adding verification_request as a category without touching it would mean a pending
-- verification request blocks the same account from appealing a ban, and an open ban appeal blocks
-- a verification request.
--
-- The first of those is the one that matters. It puts a discretionary request for a badge in the
-- way of contesting an enforcement action, which inverts the priority between them. The guard's
-- actual purpose is to stop one person flooding the support queue, and narrowing it by category
-- keeps that intact.
--
-- A verification request still gets a guard of its own, so the account cannot hold two of those
-- either. Two independent guards, one per lane, rather than one guard across both lanes.
--
-- Both statements are CREATE INDEX CONCURRENTLY, so this migration runs non-transactionally via its
-- .sql.conf sidecar. The drop is CONCURRENTLY for the same reason: a plain DROP INDEX takes an
-- ACCESS EXCLUSIVE lock on support_tickets for its duration.
--
-- Ordering note: the new general guard is built before the old one is dropped, so there is no
-- window in which an account can open two ordinary tickets. The narrower guard is strictly weaker
-- than the one it replaces, so holding both at once refuses nothing that either alone would admit.

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_support_tickets_one_open_support_per_user
    ON support_tickets (user_id)
    WHERE user_id IS NOT NULL
      AND category <> 'verification_request'
      AND status IN ('open', 'in_progress', 'escalated');

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_support_tickets_one_open_verification_per_user
    ON support_tickets (user_id)
    WHERE user_id IS NOT NULL
      AND category = 'verification_request'
      AND status IN ('open', 'in_progress', 'escalated');

DROP INDEX CONCURRENTLY IF EXISTS uq_support_tickets_one_open_per_user;
