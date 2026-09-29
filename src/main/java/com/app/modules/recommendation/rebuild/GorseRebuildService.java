package com.app.modules.recommendation.rebuild;

/**
 * Rebuilds Gorse from PostgreSQL and ClickHouse: purge, users, items, feedback, then a check that
 * fails loudly on any difference.
 *
 * <p>Built against the way the For You feed once broke silently, when Gorse's item catalogue
 * drifted from the {@code posts} table. Every Gorse call succeeded while its candidates no longer
 * resolved to live posts, and {@code auto_insert_item} created a visible item for any feedback that
 * named an unknown one. So the rebuild starts from an empty Gorse store, pushes an item for every
 * post (hidden when it is not published or is soft-deleted), sends feedback only for users and
 * items PostgreSQL knows, and compares the catalogue with PostgreSQL in both directions before it
 * calls itself done.
 */
public interface GorseRebuildService {

    /**
     * Runs the given run from its recorded phase and checkpoint to the end.
     *
     * <p>A failure is written onto the run ({@code FAILED}, or {@code FAILED_VERIFICATION} when the
     * final comparison finds a gap) and logged, not thrown, so the caller's thread ends cleanly.
     * The recommendation feedback listener is stopped for the duration and always started again.
     *
     * @param run the run to execute, created or loaded by the caller
     */
    void execute(GorseRebuildRun run);
}
