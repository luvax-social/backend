package com.app.modules.users.dto.response;

/**
 * An account's notification toggles from {@code user_settings}, read by the notification module
 * before it writes a notification.
 *
 * @param likes whether post and comment likes notify
 * @param comments whether comments and replies notify
 * @param follows whether follows and follow requests notify
 * @param mentions whether mentions notify
 * @param messages reserved for a push channel; direct messages no longer reach the activity feed
 */
public record NotificationPreferencesResponse(
        boolean likes, boolean comments, boolean follows, boolean mentions, boolean messages) {

    /** Every toggle on, matching the column defaults of a freshly created settings row. */
    public static final NotificationPreferencesResponse ALL_ENABLED =
            new NotificationPreferencesResponse(true, true, true, true, true);
}
