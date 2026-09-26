package com.app.modules.hashtag.service;

import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.app.common.response.PageResponse;
import com.app.modules.hashtag.dto.response.HashtagTrendingResponse;

/**
 * Trending hashtags ranked for one caller, blending the platform snapshot with that caller's own
 * hashtag affinity.
 *
 * <p>Returns the same response shape as the platform surface, so one client component renders both
 * with two data sources rather than two components with divergent contracts.
 */
public interface PersonalisedTrendingService {

    /**
     * Returns trending hashtags ranked for this caller.
     *
     * <p>Blends the platform snapshot with the caller's affinity <em>on rank, never on the two
     * scores directly</em>. An affinity score is a share of one user's own decayed total, so a user
     * with three hashtags scores about 0.33 on each while a user with a hundred and thirty scores
     * about 0.007 on each; a trending score is a post count over a window. Those are not the same
     * unit, and combining them numerically would rank by how broad a user's interests happen to be
     * rather than by what those interests are.
     *
     * <p>A share of the page is reserved for hashtags adjacent to the caller's interests but not
     * among them, so the list cannot collapse into a filter bubble of what the caller already
     * reads. Reserved slots are backfilled from the blend when the adjacency pool is thin, so
     * reserving them can never shorten the page.
     *
     * <p>A caller with no computed affinity silently receives the platform list. That is the normal
     * cold-start state, not an error, and the client is never handed an empty personalised tab.
     *
     * @param viewerId authenticated caller whose affinity ranks the list
     * @param pageable page bounds
     * @return trending hashtags ranked for the caller, pinned hashtags first
     */
    PageResponse<HashtagTrendingResponse> getPersonalisedTrending(UUID viewerId, Pageable pageable);
}
