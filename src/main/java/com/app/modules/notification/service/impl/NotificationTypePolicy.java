package com.app.modules.notification.service.impl;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.app.modules.notification.entity.enums.NotificationType;
import com.app.modules.notification.repository.NotificationRepository;

/**
 * Enforces {@code notification_type_configs.is_enabled}, the operator switch that stops a
 * notification type from being written at all.
 *
 * <p>The column is policy per the enum-versus-config contract, but until this release nothing read
 * it on the write path, so switching a type off changed only what the vocabulary endpoint reported.
 * The disabled set is read once per {@link #REFRESH_INTERVAL} rather than per notification: the
 * table changes by operator action, and a minute of lag is the price of not adding a query to every
 * write.
 */
@Component
public class NotificationTypePolicy {

    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);

    private final NotificationRepository notificationRepository;
    private final LongSupplier nanoClock;

    private volatile Snapshot snapshot;

    @Autowired
    public NotificationTypePolicy(NotificationRepository notificationRepository) {
        this(notificationRepository, System::nanoTime);
    }

    NotificationTypePolicy(NotificationRepository notificationRepository, LongSupplier nanoClock) {
        this.notificationRepository = notificationRepository;
        this.nanoClock = nanoClock;
    }

    /** Whether the operator has left this type enabled. */
    public boolean isEnabled(NotificationType type) {
        return !current().disabled().contains(type);
    }

    private Snapshot current() {
        Snapshot cached = snapshot;
        long now = nanoClock.getAsLong();
        if (cached != null && now - cached.loadedAt() < REFRESH_INTERVAL.toNanos()) {
            return cached;
        }
        Set<NotificationType> disabled = EnumSet.noneOf(NotificationType.class);
        for (String key : notificationRepository.findDisabledTypeKeys()) {
            // A config row whose key names no current type is ignored rather than failing every
            // notification write.
            try {
                disabled.add(NotificationType.valueOf(key.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                continue;
            }
        }
        Snapshot fresh = new Snapshot(disabled, now);
        snapshot = fresh;
        return fresh;
    }

    private record Snapshot(Set<NotificationType> disabled, long loadedAt) {}
}
