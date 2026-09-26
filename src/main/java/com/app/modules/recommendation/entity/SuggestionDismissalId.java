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

/** Composite key of one dismissal: who dismissed, and who they dismissed. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SuggestionDismissalId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "dismissed_id", nullable = false)
    private UUID dismissedId;

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SuggestionDismissalId id)) {
            return false;
        }
        return Objects.equals(userId, id.userId) && Objects.equals(dismissedId, id.dismissedId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, dismissedId);
    }
}
