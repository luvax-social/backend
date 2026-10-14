package com.app.modules.message.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.message.entity.Conversation;

/** Persistence access for {@link Conversation}. */
@Repository
public interface ConversationRepository
        extends JpaRepository<Conversation, UUID>, ConversationRepositoryCustom {

    /**
     * Restricts a conversation list to one folder. {@code primary} holds accepted conversations
     * plus the caller's own outgoing requests, whether still pending or declined, because a
     * declined request must read as a pending one to the person who sent it. {@code requests} and
     * {@code hidden_requests} hold the incoming pending requests that carry a message, split by the
     * hidden flag; an incoming request nobody has written to yet appears in neither.
     */
    String FOLDER_FILTER =
            """
			AND CASE CAST(:folder AS text)
				WHEN 'primary' THEN (c.request_status = 'accepted' OR c.request_recipient_id <> :userId)
				WHEN 'requests' THEN (c.request_status = 'pending' AND c.request_recipient_id = :userId
					AND NOT c.request_hidden AND c.last_message_at IS NOT NULL)
				WHEN 'hidden_requests' THEN (c.request_status = 'pending' AND c.request_recipient_id = :userId
					AND c.request_hidden AND c.last_message_at IS NOT NULL)
				ELSE FALSE
			END
			""";

    /**
     * Finds the existing 1-1 conversation between the two users, regardless of either participant's
     * {@code left_at} state, so a re-initiated conversation is reused rather than duplicated.
     *
     * <p>A declined request whose recipient started a fresh conversation is retired by clearing its
     * {@code direct_pair_key}, so the pair can briefly own two rows; the one still holding the key
     * is the live one and is returned.
     */
    @Query(
            value =
                    """
					SELECT c.* FROM conversations c
					WHERE EXISTS (SELECT 1 FROM conversation_participants p1
								WHERE p1.conversation_id = c.id AND p1.user_id = :userA)
					AND EXISTS (SELECT 1 FROM conversation_participants p2
								WHERE p2.conversation_id = c.id AND p2.user_id = :userB)
					AND (SELECT COUNT(*) FROM conversation_participants px
						WHERE px.conversation_id = c.id) = 2
					ORDER BY (c.direct_pair_key IS NOT NULL) DESC, c.created_at DESC
					LIMIT 1
					""",
            nativeQuery = true)
    Optional<Conversation> findDirectConversationBetween(UUID userA, UUID userB);

    /**
     * Loads a conversation and takes a row lock on it until the transaction ends.
     *
     * <p>Every send takes it, so sends into one conversation run one after another and each reads
     * the request state and the sender's message count as the previous one left them.
     *
     * @param id the conversation to lock
     * @return the conversation with its current state, or empty when it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id")
    Optional<Conversation> lockById(@Param("id") UUID id);

    /**
     * Releases a declined conversation's {@code direct_pair_key} so the pair can start a new one.
     *
     * <p>The retired row keeps its participants and messages; only the uniqueness claim on the pair
     * is dropped. The caller holds the pair lock, so no other transaction can create a conversation
     * for the pair in between.
     *
     * @param conversationId the declined conversation to retire
     * @return the number of rows updated
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE conversations SET direct_pair_key = NULL WHERE id = :conversationId",
            nativeQuery = true)
    int retireDirectPairKey(UUID conversationId);

    /**
     * Deletes the pair's conversation only when it holds no messages.
     *
     * <p>Guarded by {@code NOT EXISTS} so a conversation carrying history can never be removed as a
     * side effect of a follow change. The pair-key expression is copied verbatim from {@code
     * ConversationRepositoryImpl.LOCK_AND_KEY_SQL} so the lock and the delete can never disagree
     * about which conversation they mean.
     */
    @Modifying
    @Query(
            value =
                    """
					DELETE FROM conversations c
					WHERE c.direct_pair_key = LEAST(:userA, :userB)::text || ':' || GREATEST(:userA, :userB)::text
					AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.conversation_id = c.id)
					""",
            nativeQuery = true)
    void deleteEmptyDirectConversation(UUID userA, UUID userB);

    /**
     * First page of the caller's active conversations in one folder, newest activity first.
     *
     * <p>Conversations with no message yet ({@code last_message_at IS NULL}) sort last, per {@code
     * NULLS LAST}; continuing past them onto later pages is handled by {@link
     * #findMyConversationsBefore}. In the {@code primary} folder pinned conversations are excluded
     * here and returned in full, unpaginated, by {@link #findMyPinnedConversations} instead -
     * keeping "pinned" out of the keyset cursor avoids a second sort dimension the cursor would
     * otherwise have to encode, and a pinned set is small enough that paginating it buys nothing.
     * The request folders have no pinned section.
     *
     * @param userId the caller
     * @param folder {@code primary}, {@code requests} or {@code hidden_requests}
     * @param pageable the page size
     */
    @Query(
            value =
                    """
					SELECT c.* FROM conversations c
					JOIN conversation_participants p
						ON p.conversation_id = c.id AND p.user_id = :userId AND p.left_at IS NULL
					WHERE (CAST(:folder AS text) <> 'primary' OR p.pinned_at IS NULL)
					"""
                            + FOLDER_FILTER
                            + """
					ORDER BY c.last_message_at DESC NULLS LAST, c.id DESC
					""",
            nativeQuery = true)
    List<Conversation> findFirstMyConversations(UUID userId, String folder, Pageable pageable);

    /**
     * Keyset continuation after the {@code (lastMessageAt, conversationId)} cursor.
     *
     * <p>{@code cursorTime} is {@code null} when the cursor itself points at a {@code
     * last_message_at IS NULL} row; continuation then stays within that null group, ordered by
     * {@code id DESC}. Otherwise every null row is included unconditionally, since {@code NULLS
     * LAST} always places the whole null group after every non-null row. Pinned conversations are
     * excluded from the {@code primary} folder, matching {@link #findFirstMyConversations}.
     */
    @Query(
            value =
                    """
					SELECT c.* FROM conversations c
					JOIN conversation_participants p
						ON p.conversation_id = c.id AND p.user_id = :userId AND p.left_at IS NULL
					WHERE (CAST(:folder AS text) <> 'primary' OR p.pinned_at IS NULL)
						AND ((CAST(:cursorTime AS timestamptz) IS NOT NULL
								AND (c.last_message_at < :cursorTime
									OR (c.last_message_at = :cursorTime AND c.id < :cursorId)
									OR c.last_message_at IS NULL))
							OR (CAST(:cursorTime AS timestamptz) IS NULL
								AND c.last_message_at IS NULL AND c.id < :cursorId))
					"""
                            + FOLDER_FILTER
                            + """
					ORDER BY c.last_message_at DESC NULLS LAST, c.id DESC
					""",
            nativeQuery = true)
    List<Conversation> findMyConversationsBefore(
            UUID userId,
            String folder,
            OffsetDateTime cursorTime,
            UUID cursorId,
            Pageable pageable);

    /**
     * Every conversation the caller has pinned, most recently pinned first.
     *
     * <p>Unpaginated by design: prepended to the first page only, by the service layer, so a
     * caller's pins are always fully visible without their own cursor. A conversation the caller
     * received as a request, or one they declined, is not shown even when pinned.
     */
    @Query(
            value =
                    """
					SELECT c.* FROM conversations c
					JOIN conversation_participants p
						ON p.conversation_id = c.id AND p.user_id = :userId
							AND p.left_at IS NULL AND p.pinned_at IS NOT NULL
					WHERE (c.request_status = 'accepted' OR c.request_recipient_id <> :userId)
					ORDER BY p.pinned_at DESC
					""",
            nativeQuery = true)
    List<Conversation> findMyPinnedConversations(UUID userId);
}
