package com.app.modules.notification.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.app.modules.notification.entity.converter.NotificationCategoryConverter;
import com.app.modules.notification.entity.converter.NotificationTypeConverter;
import com.app.modules.notification.entity.enums.NotificationCategory;
import com.app.modules.notification.entity.enums.NotificationType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An activity-feed notification delivered to a recipient.
 *
 * <p>One row is either a single event or an aggregated group of identical events on one target
 * ("anna and 12 others liked your post"), whose actors are rows in {@code notification_actors}.
 * {@code activity_at} is the feed sort key and moves forward when a new actor joins a group, which
 * is why it exists beside the database-set, immutable {@code created_at}.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private UUID recipientId;

    /** The newest actor; null for a platform notice or once that account was hard-deleted. */
    @Column(name = "actor_id", nullable = true)
    private UUID actorId;

    @Convert(converter = NotificationTypeConverter.class)
    @Column(name = "type", nullable = false)
    private NotificationType type;

    @Convert(converter = NotificationCategoryConverter.class)
    @Column(name = "category", nullable = false)
    private NotificationCategory category;

    @Column(name = "entity_type", length = 50, nullable = true)
    private String entityType;

    @Column(name = "entity_id", nullable = true)
    private UUID entityId;

    @Column(name = "post_id", nullable = true, updatable = false)
    private UUID postId;

    @Column(name = "message", nullable = true, columnDefinition = "TEXT")
    private String message;

    /** The audit row behind a moderation notice; null for every other type. */
    @Column(name = "admin_action_id", nullable = true, updatable = false)
    private UUID adminActionId;

    /** Null while unread. A new actor joining a group resets it, so the group reads as new. */
    @Setter
    @Column(name = "read_at", nullable = true)
    private OffsetDateTime readAt;

    /** Set when the recipient deletes the row, or when every actor of a group has retracted. */
    @Column(name = "deleted_at", nullable = true)
    private OffsetDateTime deletedAt;

    /** Null for a type that does not aggregate. */
    @Column(name = "aggregation_key", nullable = true, updatable = false)
    private String aggregationKey;

    @Column(name = "is_group_open", nullable = false)
    private boolean groupOpen;

    @Column(name = "group_started_at", nullable = true)
    private OffsetDateTime groupStartedAt;

    /** Trigger-maintained count of {@code notification_actors} rows; never written here. */
    @Column(name = "actor_count", nullable = false, insertable = false, updatable = false)
    private int actorCount;

    /** Whether the newest actor holds a verified badge; backs the verified feed filter. */
    @Column(name = "actor_verified", nullable = false)
    private boolean actorVerified;

    @Column(name = "activity_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime activityAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
