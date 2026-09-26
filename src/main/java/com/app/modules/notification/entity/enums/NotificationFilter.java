package com.app.modules.notification.entity.enums;

import java.util.Locale;

/**
 * The chips above the activity feed.
 *
 * <p>Each predicate is written to match the partial index built for it (V121): {@code unread}
 * matches {@code idx_notifications_feed_unread}, the category filters match {@code
 * idx_notifications_feed_category}, {@code verified} matches {@code
 * idx_notifications_feed_verified}, and {@code all} rides {@code idx_notifications_feed}. The
 * predicates are fixed SQL, never built from request input.
 */
public enum NotificationFilter {
    ALL("TRUE"),
    UNREAD("n.read_at IS NULL"),
    COMMENTS("n.category = 'comment'"),
    MENTIONS("n.category = 'mention'"),
    FOLLOWS("n.category = 'follow'"),
    SYSTEM("n.category = 'system'"),
    VERIFIED("n.actor_verified");

    private final String predicate;

    NotificationFilter(String predicate) {
        this.predicate = predicate;
    }

    /** The SQL condition over alias {@code n} that selects this filter's rows. */
    public String predicate() {
        return predicate;
    }

    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses the query-string form.
     *
     * @return the filter, or null when {@code value} names none
     */
    public static NotificationFilter fromQueryValue(String value) {
        if (value == null || value.isBlank()) {
            return ALL;
        }
        try {
            return NotificationFilter.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
