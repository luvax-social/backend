package com.app.modules.message.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.message.entity.ConversationParticipant;
import com.app.modules.message.entity.ConversationParticipantId;

/** Persistence access for {@link ConversationParticipant}. */
@Repository
public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, ConversationParticipantId> {

    /** The participant gate: true only for a user actively participating (not left). */
    boolean existsByIdConversationIdAndIdUserIdAndLeftAtIsNull(UUID conversationId, UUID userId);

    Optional<ConversationParticipant> findByIdConversationIdAndIdUserId(
            UUID conversationId, UUID userId);

    /** All members (active and former) of one conversation, for detail/participant-list views. */
    List<ConversationParticipant> findByIdConversationIdOrderByJoinedAtAsc(UUID conversationId);

    /**
     * Active and former members across many conversations in one query, for batched conversation-list
     * hydration (avoids one query per conversation).
     */
    List<ConversationParticipant> findByIdConversationIdIn(
            Collection<UUID> conversationIds);

    int countByIdConversationIdAndLeftAtIsNull(UUID conversationId);

    /**
     * Active, unmuted member user ids for one conversation whose account still exists (not
     * soft-deleted), for notification fan-out recipient lookup. A participant who muted this
     * conversation is excluded here rather than filtered by the consumer, so muting is a single
     * source of truth instead of a rule duplicated at every call site.
     */
    @Query(
            value =
                    "SELECT p.user_id FROM conversation_participants p "
                            + "JOIN users u ON u.id = p.user_id "
                            + "WHERE p.conversation_id = :conversationId AND p.left_at IS NULL "
                            + "AND p.is_muted = FALSE AND u.deleted_at IS NULL",
            nativeQuery = true)
    List<UUID> findActiveUserIdsByConversationId(@Param("conversationId") UUID conversationId);
}
