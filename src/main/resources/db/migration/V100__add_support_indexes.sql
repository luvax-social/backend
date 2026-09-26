-- Indexes for the support centre.
--
-- Separate from V97 and V98, and non-transactional, because every index here is built
-- CONCURRENTLY. This is the same split V62 and V63 use for the discipline tables: create the tables
-- in a transactional migration, build their indexes in a non-transactional one.

-- The one-open-ticket invariant, enforced at the data layer.
--
-- Partial and unique: a user may hold exactly one ticket that is not terminal, across every
-- category. answered and rejected are the terminal statuses, so a user whose ticket was answered
-- may open another. pending_confirmation is deliberately outside the guard: that row is not yet a
-- real ticket, it belongs to the public form where user_id is usually null anyway, and counting it
-- would let an unconfirmed submission block the account's genuine ticket.
--
-- user_id IS NOT NULL is required as well as implied: a partial unique index over a nullable column
-- would otherwise admit unlimited public tickets, since NULL never equals NULL.
--
-- This mirrors V89, which narrowed the report duplicate guard to active statuses for exactly the
-- same reason: a guard that counts closed rows stops a user from ever asking again.
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_support_tickets_one_open_per_user
    ON support_tickets (user_id)
    WHERE user_id IS NOT NULL
      AND status IN ('open', 'in_progress', 'escalated');

-- The staff queue: newest first, filtered by status, with the identifier as the keyset tiebreaker.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_support_tickets_status_created
    ON support_tickets (status, created_at DESC, id DESC);

-- A user reading their own tickets.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_support_tickets_user_created
    ON support_tickets (user_id, created_at DESC, id DESC)
    WHERE user_id IS NOT NULL;

-- The conflict-of-interest check resolves a ticket to its audit row, and the administrative view
-- lists every ticket appealing one action.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_support_tickets_admin_action
    ON support_tickets (admin_action_id)
    WHERE admin_action_id IS NOT NULL;

-- A staff member's own claimed queue.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_support_tickets_assigned
    ON support_tickets (assigned_to, created_at DESC, id DESC)
    WHERE assigned_to IS NOT NULL;
