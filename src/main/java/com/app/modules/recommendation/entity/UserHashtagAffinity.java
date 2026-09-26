package com.app.modules.recommendation.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How strongly one user leans toward one hashtag, over the window the score was computed for.
 *
 * <p>A derived read model, rebuildable in full from {@code user_events} joined to {@code
 * post_hashtags}: losing the table costs only the next scheduled recompute. Rows are written
 * exclusively by the affinity job; no request path may write them.
 *
 * <p>{@code score} is the user's own share, so values for one user sum to approximately 1 and are
 * comparable between a user with thousands of events and one with dozens.
 */
@Entity
@Table(name = "user_hashtag_affinity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserHashtagAffinity {

    @EmbeddedId private UserHashtagAffinityId id;

    @Column(name = "score", nullable = false)
    private BigDecimal score;

    @Column(name = "weight", nullable = false)
    private BigDecimal weight;

    @Column(name = "event_count", nullable = false)
    private int eventCount;

    @Column(name = "window_start", nullable = false)
    private OffsetDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private OffsetDateTime windowEnd;

    @Column(name = "computed_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime computedAt;
}
