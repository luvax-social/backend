package com.app.modules.users.service;

import java.util.UUID;

import com.app.modules.users.dto.response.NotificationPreferencesResponse;

/** Read access to an account's notification toggles for the notification module. */
public interface UserNotificationPreferencesService {

    /**
     * Returns the account's notification toggles.
     *
     * <p>Every account has a settings row from creation; an absent row (which only direct SQL can
     * produce) reads as every toggle on, the column defaults, so a missing row never silently
     * suppresses a notification.
     *
     * @param userId the recipient whose toggles are read
     * @return the toggles, never null
     */
    NotificationPreferencesResponse findNotificationPreferences(UUID userId);
}
