package com.app.modules.recommendation.client;

import java.util.List;

/**
 * Empties Gorse's own store, the {@code gorse} database that sits beside the application database.
 *
 * <p>Gorse's REST API has no bulk delete and its purge endpoint does not accept this deployment's
 * credentials, so the tables are truncated directly. Shared by the seed reset, which tolerates a
 * failure, and the operator-triggered rebuild, which does not.
 */
public interface GorsePurger {

    /**
     * Lists what stops {@link #purge()} from succeeding, without changing anything.
     *
     * <p>One entry per Gorse table the application role cannot {@code TRUNCATE}, worded so an
     * operator can grant exactly that, and one per table Gorse has not created. An empty list means
     * the purge can run.
     *
     * @return the obstacles, empty when the purge is possible
     * @throws GorsePurgeException when the Gorse database cannot be reached at all
     */
    List<String> findPurgeObstacles();

    /**
     * Truncates every Gorse table, so no user, item or feedback of an earlier dataset survives.
     *
     * @throws GorsePurgeException when the database cannot be reached or a truncation fails
     */
    void purge();
}
