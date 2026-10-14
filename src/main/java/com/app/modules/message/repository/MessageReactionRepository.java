package com.app.modules.message.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.message.entity.MessageReaction;

/** Persistence access for {@link MessageReaction}. */
@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, UUID> {

    /**
     * Sets a user's reaction to a message, replacing an earlier emoji.
     *
     * <p>{@code reacted_at} is stamped with the database clock and moves only when the emoji
     * changes, so repeating the same reaction is a no-op. An administrative tombstone on the row is
     * left alone: the owner choosing another emoji does not undo a moderation decision.
     *
     * @param messageId the reacted-to message
     * @param userId the reacting user
     * @param emoji the validated emoji
     * @return 1 when a reaction was created or changed, 0 when the same emoji was already set
     */
    @Modifying
    @Query(
            value =
                    """
					INSERT INTO message_reactions (message_id, user_id, emoji, reacted_at)
					VALUES (:messageId, :userId, :emoji, NOW())
					ON CONFLICT (message_id, user_id) DO UPDATE
					SET emoji = EXCLUDED.emoji, reacted_at = NOW()
					WHERE message_reactions.emoji <> EXCLUDED.emoji
					""",
            nativeQuery = true)
    int upsert(
            @Param("messageId") UUID messageId,
            @Param("userId") UUID userId,
            @Param("emoji") String emoji);

    /**
     * Removes a user's reaction to a message.
     *
     * @param messageId the message
     * @param userId the reacting user
     * @return the number of rows removed, 0 when the user had no reaction
     */
    @Modifying
    @Query(
            value =
                    "DELETE FROM message_reactions WHERE message_id = :messageId"
                            + " AND user_id = :userId",
            nativeQuery = true)
    int deleteReaction(@Param("messageId") UUID messageId, @Param("userId") UUID userId);

    /** Reads a user's own reaction row to a message, whatever its tombstone says. */
    Optional<MessageReaction> findByMessageIdAndUserId(UUID messageId, UUID userId);

    /**
     * Reads the reactions a viewer may see on a page of messages, oldest first.
     *
     * <p>A reaction is left out when it or its message carries a tombstone, so a deleted or removed
     * message shows none and a restore brings them back, and when a block exists between the viewer
     * and the reacting user in either direction.
     *
     * @param messageIds the messages being rendered
     * @param viewerId the user reading the page
     * @return the visible reactions
     */
    @Query(
            value =
                    """
					SELECT r.* FROM message_reactions r
					JOIN messages m ON m.id = r.message_id
					WHERE r.message_id IN (:messageIds)
					AND r.admin_removed_at IS NULL
					AND m.is_deleted = FALSE
					AND m.admin_removed_at IS NULL
					AND NOT EXISTS (
						SELECT 1 FROM blocks b
						WHERE (b.blocker_id = :viewerId AND b.blocked_id = r.user_id)
							OR (b.blocker_id = r.user_id AND b.blocked_id = :viewerId))
					ORDER BY r.reacted_at, r.id
					""",
            nativeQuery = true)
    List<MessageReaction> findVisibleForMessages(
            @Param("messageIds") Collection<UUID> messageIds, @Param("viewerId") UUID viewerId);

    /**
     * Reads the newest reaction the viewer may see in each of the given conversations.
     *
     * <p>Applies the same visibility rules as {@link #findVisibleForMessages}. One statement for
     * the whole conversation list avoids a query per row.
     *
     * @param conversationIds the conversations being listed
     * @param viewerId the user reading the list
     * @return at most one reaction per conversation
     */
    @Query(
            value =
                    """
					SELECT DISTINCT ON (m.conversation_id) r.*
					FROM message_reactions r
					JOIN messages m ON m.id = r.message_id
					WHERE m.conversation_id IN (:conversationIds)
					AND r.admin_removed_at IS NULL
					AND m.is_deleted = FALSE
					AND m.admin_removed_at IS NULL
					AND NOT EXISTS (
						SELECT 1 FROM blocks b
						WHERE (b.blocker_id = :viewerId AND b.blocked_id = r.user_id)
							OR (b.blocker_id = r.user_id AND b.blocked_id = :viewerId))
					ORDER BY m.conversation_id, r.reacted_at DESC, r.id DESC
					""",
            nativeQuery = true)
    List<MessageReaction> findLatestVisiblePerConversation(
            @Param("conversationIds") Collection<UUID> conversationIds,
            @Param("viewerId") UUID viewerId);
}
