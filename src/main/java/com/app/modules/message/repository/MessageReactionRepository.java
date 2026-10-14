package com.app.modules.message.repository;

import java.time.OffsetDateTime;
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
     * Removes a user's reaction to a message and returns the row it removed.
     *
     * <p>One statement, so the caller decides whether a removal event is owed from the very row
     * that was deleted, including whether an administrator had already hidden it, instead of from a
     * separate read that a concurrent reaction could invalidate.
     *
     * @param messageId the message
     * @param userId the reacting user
     * @return the removed row, or empty when the user had no reaction
     */
    @Query(
            value =
                    "DELETE FROM message_reactions WHERE message_id = :messageId"
                            + " AND user_id = :userId RETURNING *",
            nativeQuery = true)
    Optional<MessageReaction> deleteReaction(
            @Param("messageId") UUID messageId, @Param("userId") UUID userId);

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
     * Reads the newest reaction the viewer may see in each of the given conversations, counting
     * only reactions newer than the conversation's last message.
     *
     * <p>The list shows a reaction only when it is the newest thing in the thread, so a reaction at
     * or before {@code conversations.last_message_at} is never needed. Driving the read from {@code
     * reacted_at} against that bound keeps the cost proportional to the reactions that arrived
     * after the last message instead of every message of every listed conversation, and it is the
     * reason the read joins {@code conversations}. Applies the same visibility rules as {@link
     * #findVisibleForMessages}; one statement for the whole list avoids a query per row.
     *
     * @param conversationIds the conversations being listed
     * @param viewerId the user reading the list
     * @return at most one reaction per conversation
     */
    @Query(
            value =
                    """
					SELECT DISTINCT ON (c.id) r.*
					FROM conversations c
					JOIN message_reactions r ON r.reacted_at > c.last_message_at
					JOIN messages m ON m.id = r.message_id AND m.conversation_id = c.id
					WHERE c.id IN (:conversationIds)
					AND r.admin_removed_at IS NULL
					AND m.is_deleted = FALSE
					AND m.admin_removed_at IS NULL
					AND NOT EXISTS (
						SELECT 1 FROM blocks b
						WHERE (b.blocker_id = :viewerId AND b.blocked_id = r.user_id)
							OR (b.blocker_id = r.user_id AND b.blocked_id = :viewerId))
					ORDER BY c.id, r.reacted_at DESC, r.id DESC
					""",
            nativeQuery = true)
    List<MessageReaction> findLatestVisiblePerConversation(
            @Param("conversationIds") Collection<UUID> conversationIds,
            @Param("viewerId") UUID viewerId);

    /**
     * Reads the account that authored a reaction, for the moderation path.
     *
     * <p>Native and taking no conversation identifier, because moderation reaches a reaction
     * through a report rather than through a thread the actor participates in.
     *
     * @param reactionId the reaction's own identifier
     * @return the reacting account's id, or empty when no row holds that id
     */
    @Query(
            value = "SELECT r.user_id FROM message_reactions r WHERE r.id = :reactionId",
            nativeQuery = true)
    Optional<UUID> findReactorIdForModeration(@Param("reactionId") UUID reactionId);

    /**
     * Reports whether a reaction currently carries the administrative tombstone.
     *
     * @param reactionId the reaction's own identifier
     * @return true when the row carries an {@code admin_removed_at}, or empty when no row holds
     *     that id
     */
    @Query(
            value =
                    "SELECT r.admin_removed_at IS NOT NULL FROM message_reactions r"
                            + " WHERE r.id = :reactionId",
            nativeQuery = true)
    Optional<Boolean> isAdminRemoved(@Param("reactionId") UUID reactionId);

    /**
     * Sets or clears a reaction's administrative tombstone.
     *
     * <p>Touches that column only, so the emoji and {@code reacted_at} survive a removal and a
     * restore returns the reaction exactly as it was, and the owner's own choices (picking another
     * emoji, removing the reaction) are never undone by a moderation decision.
     *
     * @param reactionId the reaction's own identifier
     * @param adminRemovedAt removal timestamp, or null when restoring
     * @return number of updated reactions
     */
    @Modifying
    @Query(
            value =
                    "UPDATE message_reactions SET admin_removed_at = :adminRemovedAt"
                            + " WHERE id = :reactionId",
            nativeQuery = true)
    int applyAdminModeration(
            @Param("reactionId") UUID reactionId,
            @Param("adminRemovedAt") OffsetDateTime adminRemovedAt);
}
