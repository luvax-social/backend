package com.app.modules.notification.entity.enums;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The feed filter a notification belongs to, mirroring the {@code notification_category} database
 * enum (V115).
 *
 * <p>Each {@link NotificationType} maps to exactly one category through {@link
 * NotificationType#category()}; the column is written from that mapping and never derived in SQL.
 */
public enum NotificationCategory {
    LIKE,
    COMMENT,
    MENTION,
    FOLLOW,
    STORY,
    MESSAGE,
    SYSTEM;

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses the database or wire form, which is the lowercase constant name. */
    public static NotificationCategory fromValue(String value) {
        return NotificationCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
