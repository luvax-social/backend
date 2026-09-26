package com.app.modules.recommendation.entity;

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
 * One account a viewer permanently removed from their own suggestions.
 *
 * <p>Not a block. The dismissed account still appears in search, on profiles, in the follow graph
 * and everywhere else it would normally appear; only this one surface stops offering it.
 */
@Entity
@Table(name = "suggestion_dismissals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionDismissal {

    @EmbeddedId private SuggestionDismissalId id;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
