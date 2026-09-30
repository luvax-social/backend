package com.app.modules.recommendation.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Binding for the {@code app.recommendation} namespace: the read-set query bounds used by {@link
 * com.app.modules.recommendation.service.impl.feed.RecommendationSource}'s exhaustion topup, and
 * the over-fetch factor applied to the trending chunk it draws from.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.recommendation")
public class RecommendationProperties {

    // Matches the affinity window and the span the seeded dataset covers. A read older than this
    // is invisible to the topup's second pass, which only degrades the pass to "show something
    // read long ago" rather than failing - see DATA_RULES.md.
    private Duration readSetWindow = Duration.ofDays(90);

    // Caps the read-set query's row count regardless of window size, so a very active account
    // cannot turn the topup path into an unbounded read. A user whose read history exceeds this
    // within the window gets an incomplete read-set - the same documented degradation as the
    // window bound.
    private int readSetMaxRows = 2000;

    // Trending chunk size is shortfall * this multiplier: some of the fetched chunk is discarded
    // as already-returned-by-gorse or as a read item once the unread pass is already satisfied
    // (see RecommendationSource), so the raw fetch must exceed the shortfall to have good odds of
    // filling it.
    private int topUpOverfetchMultiplier = 3;
}
