-- Served by the retention purge, which deletes PUBLISHED rows older than the window oldest first,
-- and by the metrics sampler's PUBLISHED count.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_events_published_at
    ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- Served by the metrics sampler's DEAD count and the dead-row alert. DEAD rows are never purged.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_events_dead
    ON outbox_events (created_at)
    WHERE status = 'DEAD';
