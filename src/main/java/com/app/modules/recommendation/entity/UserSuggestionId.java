package com.app.modules.recommendation.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Composite key of one precomputed suggestion: who it is for, and who it offers. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserSuggestionId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "suggested_id", nullable = false)
    private UUID suggestedId;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof UserSuggestionId id)) {
            return false;
        }
        return Objects.equals(userId, id.userId) && Objects.equals(suggestedId, id.suggestedId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, suggestedId);
    }
}
