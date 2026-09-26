package com.app.modules.recommendation.service;

import java.time.OffsetDateTime;

/**
 * Outcome of one affinity recompute run.
 *
 * @param rowsWritten affinity rows inserted or updated
 * @param staleRowsRemoved rows the run did not refresh and therefore deleted
 * @param windowStart inclusive lower bound the run read from
 * @param windowEnd exclusive upper bound the run read to
 */
public record AffinityRecomputeResult(
        int rowsWritten,
        int staleRowsRemoved,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd) {}
