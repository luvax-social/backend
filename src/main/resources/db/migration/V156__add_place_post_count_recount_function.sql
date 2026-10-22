-- Repair function for the trigger-maintained places.post_count.
--
-- The counter is owned by trg_place_post_count and application code never writes it. If it ever
-- looks stale the correct action is to recompute it from posts, which this function does in one
-- statement and reports how many places it changed. It is safe to run at any time.

CREATE OR REPLACE FUNCTION fn_recount_place_posts()
RETURNS INTEGER AS $$
DECLARE
    changed INTEGER;
BEGIN
    UPDATE places pl
    SET post_count = COALESCE(c.n, 0)
    FROM places p2
    LEFT JOIN (
        SELECT place_id, count(*)::INT AS n
        FROM posts
        WHERE status = 'published' AND deleted_at IS NULL AND place_id IS NOT NULL
        GROUP BY place_id
    ) c ON c.place_id = p2.id
    WHERE pl.id = p2.id
      AND pl.post_count IS DISTINCT FROM COALESCE(c.n, 0);
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed;
END;
$$ LANGUAGE plpgsql;
