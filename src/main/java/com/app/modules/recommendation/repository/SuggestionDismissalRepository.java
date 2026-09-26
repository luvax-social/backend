package com.app.modules.recommendation.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.recommendation.entity.SuggestionDismissal;
import com.app.modules.recommendation.entity.SuggestionDismissalId;

@Repository
public interface SuggestionDismissalRepository
        extends JpaRepository<SuggestionDismissal, SuggestionDismissalId> {

    /**
     * Records a dismissal, ignoring a repeat.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a read-then-write, so dismissing twice is
     * harmless and does not race. A dismissal is permanent and has no undo, so there is nothing for
     * a second call to change.
     *
     * @param viewerId the account dismissing
     * @param dismissedId the account being removed from that viewer's suggestions
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO suggestion_dismissals (user_id, dismissed_id)"
                            + " VALUES (:viewerId, :dismissedId)"
                            + " ON CONFLICT (user_id, dismissed_id) DO NOTHING",
            nativeQuery = true)
    void dismiss(@Param("viewerId") UUID viewerId, @Param("dismissedId") UUID dismissedId);
}
