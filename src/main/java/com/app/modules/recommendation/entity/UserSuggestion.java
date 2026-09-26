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
 * One precomputed people-you-may-know candidate.
 *
 * <p>A read model, never a source of truth. Every row is derivable from the follow graph, the
 * behavioural event log, the hashtag affinity model and the recommender, so the table can be
 * truncated and rebuilt without losing anything.
 *
 * <p>Presence here is not sufficient for a row to be shown. Every exclusion rule is re-applied at
 * read time, because a twelve-hour cycle would otherwise keep offering an account the viewer
 * followed, blocked or dismissed since the job last ran.
 */
@Entity
@Table(name = "user_suggestions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSuggestion {

    @EmbeddedId private UserSuggestionId id;

    /** Position in the blended list when the job ran, 1-based. */
    @Column(name = "rank", nullable = false)
    private short rank;

    /** The fused reciprocal-rank score, kept for explainability rather than for ordering. */
    @Column(name = "score", nullable = false)
    private BigDecimal score;

    /** Comma-separated contributing sources from {@code graph|gorse|affinity|verified}. */
    @Column(name = "sources", nullable = false, columnDefinition = "TEXT")
    private String sources;

    @Column(name = "computed_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime computedAt;
}
