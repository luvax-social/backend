package com.app.modules.users.service;

import java.util.UUID;

import com.app.modules.users.enums.MessagePermission;

/**
 * Read access to the privacy preferences other modules enforce against an account.
 *
 * <p>Every account has exactly one {@code user_settings} row from creation. These readers serve
 * another account's comment or message path, so a missing row logs an error and answers the column
 * defaults instead of failing the actor. The owner's own settings read stays strict.
 */
public interface UserPrivacySettingsService {

    /**
     * Returns whether the platform-wide hidden word list applies to the account's content.
     *
     * @param userId the account whose preference is read
     * @return the stored toggle, or {@code true} when the account has no settings row
     */
    boolean hiddenWordsSystemEnabled(UUID userId);

    /**
     * Returns who may start a direct conversation with the account.
     *
     * @param userId the account whose preference is read
     * @return the stored permission, or {@code EVERYONE} when the account has no settings row
     */
    MessagePermission messagePermissionOf(UUID userId);
}
