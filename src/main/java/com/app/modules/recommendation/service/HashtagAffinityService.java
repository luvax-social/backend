package com.app.modules.recommendation.service;

import java.util.List;
import java.util.UUID;

import com.app.modules.recommendation.entity.UserHashtagAffinity;

/**
 * Owns the {@code user_hashtag_affinity} derived read model: its periodic recomputation and the
 * reads that consume it.
 */
public interface HashtagAffinityService {

    /**
     * Recomputes affinity for every user with activity in the current window.
     *
     * <p>Refreshes qualifying rows and removes rows this run did not touch, both in one
     * transaction, so a reader sees either the whole previous state or the whole new one. Designed
     * to be harmless if run twice: the write is an upsert against the composite key, following the
     * same single-instance assumption {@code platform_stats} makes.
     *
     * <p>A user with no events in the window ends with no rows. That is the correct outcome and the
     * read path handles it; the job never fabricates a row to avoid an empty result.
     *
     * @return outcome counts for the run
     */
    AffinityRecomputeResult recompute();

    /**
     * Highest-scoring hashtags for one user.
     *
     * @param userId user whose affinities are read
     * @param limit maximum rows returned
     * @return affinity rows strongest first; empty when the user has no computed affinity
     */
    List<UserHashtagAffinity> findTopForUser(UUID userId, int limit);

    /**
     * Whether the user has any computed affinity at all.
     *
     * <p>Read by personalised surfaces to decide whether to serve a personalised list or fall back
     * to the platform one, rather than handing a client an empty personalised tab.
     *
     * @param userId user to test
     * @return true when at least one affinity row exists for the user
     */
    boolean hasAffinity(UUID userId);
}
