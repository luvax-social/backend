package com.app.modules.message.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.message.entity.Message;

/** Persistence access for {@link Message}. */
@Repository
public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findByIdAndConversationId(UUID id, UUID conversationId);

    /**
     * Loads the messages a set of replies point at, whatever either tombstone says.
     *
     * <p>Native so the reply preview can report a deleted or removed parent as unavailable instead
     * of losing it, and so it costs one statement per page rather than one per reply.
     *
     * @param ids the replied-to message ids
     * @return the matching rows, tombstoned ones included
     */
    @Query(value = "SELECT m.* FROM messages m WHERE m.id IN (:ids)", nativeQuery = true)
    List<Message> findReplyParents(@Param("ids") Collection<UUID> ids);

    /**
     * Counts every message a user has sent into a conversation, tombstoned ones included.
     *
     * <p>Deleted messages count on purpose: a requester who deletes the one message a pending
     * request allows must not be handed a second.
     */
    long countByConversationIdAndSenderId(UUID conversationId, UUID senderId);

    /**
     * First page of a conversation's history, newest first. Deleted (tombstoned) messages are
     * included so clients can render a "message deleted" placeholder in place.
     *
     * <p>Ties on {@code created_at} are broken by {@code id}, so the order is total and the cursor
     * of {@link #findByConversationBefore} can resume exactly where this page stopped.
     */
    @Query(
            "SELECT m FROM Message m WHERE m.conversationId = :conversationId "
                    + "ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findFirstByConversation(
            @Param("conversationId") UUID conversationId, Pageable pageable);

    /**
     * Keyset page of a conversation's history strictly after the {@code (created_at, id)} cursor in
     * newest-first order.
     *
     * <p>The id breaks ties between messages sharing a timestamp, so a tie group that straddles a
     * page boundary is neither skipped nor repeated.
     */
    @Query(
            "SELECT m FROM Message m WHERE m.conversationId = :conversationId "
                    + "AND (m.createdAt < :cursorTime "
                    + "OR (m.createdAt = :cursorTime AND m.id < :cursorId)) "
                    + "ORDER BY m.createdAt DESC, m.id DESC")
    List<Message> findByConversationBefore(
            @Param("conversationId") UUID conversationId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * One row per conversation - its most recent message - for batched conversation-list preview
     * hydration. Avoids one query per conversation.
     */
    @Query(
            value =
                    """
					SELECT DISTINCT ON (m.conversation_id) m.* FROM messages m
					WHERE m.conversation_id IN (:conversationIds)
					ORDER BY m.conversation_id, m.created_at DESC, m.id DESC
					""",
            nativeQuery = true)
    List<Message> findLastMessagePerConversation(
            @Param("conversationIds") List<UUID> conversationIds);

    /**
     * Batched unread-message count per conversation for the given user: messages not sent by the
     * user, not deleted, and newer than the user's {@code last_read_at} (or all of them, if the
     * user has never read the conversation).
     *
     * <p>Uses {@code IS DISTINCT FROM} rather than {@code <>} so a message whose sender was later
     * deleted (nullable {@code sender_id}, V32) still counts as unread instead of being silently
     * excluded by a NULL comparison.
     */
    @Query(
            value =
                    """
					SELECT m.conversation_id AS conversationId, COUNT(*) AS unreadCount
					FROM messages m
					JOIN conversation_participants p
						ON p.conversation_id = m.conversation_id
						AND p.user_id = :userId
						AND p.left_at IS NULL
					WHERE m.conversation_id IN (:conversationIds)
					AND m.is_deleted = FALSE
					AND m.admin_removed_at IS NULL
					AND m.sender_id IS DISTINCT FROM :userId
					AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)
					GROUP BY m.conversation_id
					""",
            nativeQuery = true)
    List<ConversationUnreadCount> countUnreadPerConversation(
            @Param("userId") UUID userId, @Param("conversationIds") List<UUID> conversationIds);

    /**
     * Total unread-message count across every active conversation for the given user; same counting
     * rule as {@link #countUnreadPerConversation} without the per-conversation breakdown.
     *
     * <p>A message request the user received is not counted, whether pending or declined: it is
     * surfaced by {@link #countPendingRequests} instead, so a stranger's first message never
     * inflates the badge on the regular inbox.
     */
    @Query(
            value =
                    """
					SELECT COUNT(*)
					FROM messages m
					JOIN conversation_participants p
						ON p.conversation_id = m.conversation_id
						AND p.user_id = :userId
						AND p.left_at IS NULL
					JOIN conversations c ON c.id = m.conversation_id
					WHERE m.is_deleted = FALSE
					AND m.admin_removed_at IS NULL
					AND m.sender_id IS DISTINCT FROM :userId
					AND (c.request_status = 'accepted' OR c.request_recipient_id <> :userId)
					AND (p.last_read_at IS NULL OR m.created_at > p.last_read_at)
					""",
            nativeQuery = true)
    long countTotalUnreadForUser(@Param("userId") UUID userId);

    /**
     * Counts the user's incoming message requests that are pending, not filed as hidden, and carry
     * at least one message; the figure behind the requests badge.
     *
     * <p>A request nobody has written to yet is not counted, because the requests folder does not
     * list it either, and a hidden request is not counted because the recipient chose not to be
     * told about that folder's contents.
     *
     * @param userId the recipient
     * @return the number of visible incoming requests
     */
    @Query(
            value =
                    """
					SELECT COUNT(*)
					FROM conversations c
					JOIN conversation_participants p
						ON p.conversation_id = c.id AND p.user_id = :userId AND p.left_at IS NULL
					WHERE c.request_status = 'pending'
					AND c.request_recipient_id = :userId
					AND NOT c.request_hidden
					AND c.last_message_at IS NOT NULL
					AND NOT EXISTS (
						SELECT 1 FROM blocks b
						JOIN conversation_participants op
							ON op.conversation_id = c.id AND op.user_id <> :userId
						WHERE (b.blocker_id = :userId AND b.blocked_id = op.user_id)
							OR (b.blocker_id = op.user_id AND b.blocked_id = :userId))
					""",
            nativeQuery = true)
    long countPendingRequests(@Param("userId") UUID userId);

    /**
     * Reads a message's sender whatever either tombstone says, for the moderation path.
     *
     * <p>Native and taking no conversation identifier, because the moderation path reaches a
     * message through a report rather than through a thread the actor participates in.
     *
     * @param messageId message identifier
     * @return the sender's id, which is null for a message whose sender's account was deleted, or
     *     empty when no row holds that id
     */
    @Query(value = "SELECT m.sender_id FROM messages m WHERE m.id = :messageId", nativeQuery = true)
    Optional<UUID> findSenderIdForModeration(@Param("messageId") UUID messageId);

    /**
     * Reports whether a message currently carries the administrative tombstone.
     *
     * <p>Deliberately reads {@code admin_removed_at} rather than {@code is_deleted}: the sender's
     * own deletion is not a moderation state and must not satisfy a restore's transition guard.
     *
     * @param messageId message identifier
     * @return true when the row carries an {@code admin_removed_at}, or empty when no row holds
     *     that id
     */
    @Query(
            value =
                    "SELECT m.admin_removed_at IS NOT NULL FROM messages m WHERE m.id ="
                            + " :messageId",
            nativeQuery = true)
    Optional<Boolean> isAdminRemoved(@Param("messageId") UUID messageId);

    /**
     * Sets or clears a message's administrative tombstone.
     *
     * <p>Touches that column only. {@code content} is preserved so a restore can return the
     * message, and the sender-owned {@code is_deleted}/{@code deleted_at} pair is left alone so a
     * restore cannot undo a deletion the sender performed.
     *
     * @param messageId message identifier
     * @param adminRemovedAt removal timestamp, or null when restoring
     * @return number of updated messages
     */
    @Modifying
    @Query(
            value = "UPDATE messages SET admin_removed_at = :adminRemovedAt WHERE id = :messageId",
            nativeQuery = true)
    int applyAdminModeration(
            @Param("messageId") UUID messageId,
            @Param("adminRemovedAt") OffsetDateTime adminRemovedAt);
}
