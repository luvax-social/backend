package com.app.modules.notification.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.app.modules.notification.entity.Notification;

/**
 * The JPA view of {@code notifications}.
 *
 * <p>The feed is read by {@link NotificationFeedRepository} and written by {@link
 * NotificationAggregationRepository}; this repository keeps the entity mapping validated against
 * the schema and serves the notification type policy.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Keys of the notification types an operator has switched off in {@code
     * notification_type_configs}.
     */
    @Query(
            value = "SELECT type_key FROM notification_type_configs WHERE NOT is_enabled",
            nativeQuery = true)
    List<String> findDisabledTypeKeys();
}
