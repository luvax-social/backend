package com.app.modules.notification.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.modules.notification.entity.Notification;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * First keyset page of a recipient's notifications, newest first, excluding any notification
     * whose actor is in a block relationship with the recipient.
     *
     * <p>Paired with {@link #findByRecipientBefore}; the no-cursor variant avoids binding a null
     * cursor tuple.
     *
     * <p>The block-exclusion subquery correlates on {@code actor_id}, which is nullable for
     * system-generated notifications; a null {@code actor_id} never matches either side of the
     * comparison, so it can never be excluded and needs no separate null guard.
     *
     * @param recipientId notification recipient
     * @param pageable page size carrier
     * @return notifications ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM notifications WHERE recipient_id = :recipientId "
                            + "AND deleted_at IS NULL "
                            + "AND NOT EXISTS (SELECT 1 FROM blocks b"
                            + " WHERE (b.blocker_id = :recipientId AND b.blocked_id = notifications.actor_id)"
                            + " OR (b.blocker_id = notifications.actor_id AND b.blocked_id = :recipientId)) "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Notification> findFirstByRecipient(
            @Param("recipientId") UUID recipientId, Pageable pageable);

    /**
     * Keyset page of a recipient's notifications strictly after the cursor tuple, newest first,
     * excluding any notification whose actor is in a block relationship with the recipient.
     *
     * <p>The {@code (created_at, id)} row-value comparison seeks directly to the cursor position
     * and never drops notifications sharing a boundary {@code created_at}. Served exactly by {@code
     * idx_notifications_recipient_created_id} (V39); the block exclusion joins on {@code
     * idx_blocks_blocker} / {@code idx_blocks_blocked} (V15). See {@link #findFirstByRecipient} for
     * the null-actor note.
     *
     * @param recipientId notification recipient
     * @param cursorTime {@code created_at} of the cursor row; never null
     * @param cursorId id of the cursor row, breaking ties on equal {@code created_at}; never null
     * @param pageable page size carrier
     * @return notifications ordered by the {@code (created_at, id)} tuple descending
     */
    @Query(
            value =
                    "SELECT * FROM notifications WHERE recipient_id = :recipientId "
                            + "AND deleted_at IS NULL "
                            + "AND (created_at, id) < (:cursorTime, :cursorId) "
                            + "AND NOT EXISTS (SELECT 1 FROM blocks b"
                            + " WHERE (b.blocker_id = :recipientId AND b.blocked_id = notifications.actor_id)"
                            + " OR (b.blocker_id = notifications.actor_id AND b.blocked_id = :recipientId)) "
                            + "ORDER BY created_at DESC, id DESC",
            nativeQuery = true)
    List<Notification> findByRecipientBefore(
            @Param("recipientId") UUID recipientId,
            @Param("cursorTime") OffsetDateTime cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * Returns the count of unread notifications for the given recipient, excluding any whose actor
     * is in a block relationship with the recipient, so the badge count matches what {@link
     * #findFirstByRecipient} would render.
     *
     * @param recipientId notification recipient
     * @return unread, unblocked notification count
     */
    @Query(
            value =
                    "SELECT COUNT(*) FROM notifications WHERE recipient_id = :recipientId "
                            + "AND read_at IS NULL AND deleted_at IS NULL "
                            + "AND NOT EXISTS (SELECT 1 FROM blocks b"
                            + " WHERE (b.blocker_id = :recipientId AND b.blocked_id = notifications.actor_id)"
                            + " OR (b.blocker_id = notifications.actor_id AND b.blocked_id = :recipientId))",
            nativeQuery = true)
    long countByRecipientIdAndIsReadFalse(@Param("recipientId") UUID recipientId);

    /**
     * Looks up a single notification by its id scoped to a specific recipient. Used for ownership
     * validation before marking as read.
     */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);

    /** Marks all unread notifications for the recipient as read in a single bulk update. */
    @Modifying
    @Query(
            "UPDATE Notification n SET n.readAt = :now "
                    + "WHERE n.recipientId = :recipientId AND n.readAt IS NULL")
    int markAllAsRead(@Param("recipientId") UUID recipientId, @Param("now") OffsetDateTime now);
}
