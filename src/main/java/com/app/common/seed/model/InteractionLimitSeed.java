package com.app.common.seed.model;

import java.util.List;

/**
 * One account's interaction limit, as authored in {@code safety/interaction_control.json}.
 *
 * <p>The limit started {@code startedDaysAgo} days before the seed clock and runs {@code
 * durationDays}, so it is active when that sum leaves it ending after the clock and expired
 * otherwise. {@code recentFollowers} names accounts whose follow was approved recently enough to
 * fall inside the limit's recent-follower window, even though their request is old.
 */
public record InteractionLimitSeed(
        String owner,
        boolean limitNonFollowers,
        boolean limitRecentFollowers,
        int durationDays,
        int startedDaysAgo,
        List<String> recentFollowers) {

    public InteractionLimitSeed {
        recentFollowers = recentFollowers == null ? List.of() : List.copyOf(recentFollowers);
    }
}
