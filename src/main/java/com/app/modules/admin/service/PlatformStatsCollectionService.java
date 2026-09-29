package com.app.modules.admin.service;

import java.time.OffsetDateTime;

/** Computes one half-hour bucket of platform statistics and hands it to the analytics store. */
public interface PlatformStatsCollectionService {

    /**
     * Computes every metric for one bucket and enqueues the result as one outbox event.
     *
     * <p>Safe to repeat. Gauges are bounded by the bucket end and flows by both edges, so running
     * again for a past bucket reproduces the numbers it produced the first time rather than
     * overwriting them with the present. The event carries the time of the computation, and when
     * the store holds two collections of one bucket it keeps the later. That is what makes
     * re-running after an incident safe.
     *
     * @param bucketStart inclusive start of the bucket
     * @return number of dimension rows the event carries across every metric
     */
    int collectBucket(OffsetDateTime bucketStart);
}
