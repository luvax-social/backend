package com.app.modules.users.service;

import java.util.UUID;

import com.app.modules.users.enums.MessagePermission;

/**
 * Read access to the privacy preferences other modules enforce against an account.
 *
 * <p>Both methods treat a missing {@code user_settings} row as a broken invariant, because every
 * account has exactly one from creation, and answer {@code NOT_FOUND} instead of guessing a
 * default.
 */
public interface UserPrivacySettingsService {

    /**
     * Returns whether the platform-wide hidden word list applies to the account's content.
     *
     * @param userId the account whose preference is read
     * @throws com.app.common.exception.AppException with {@code NOT_FOUND} if no settings row
     *     exists
     */
    boolean hiddenWordsSystemEnabled(UUID userId);

    /**
     * Returns who may start a direct conversation with the account.
     *
     * @param userId the account whose preference is read
     * @throws com.app.common.exception.AppException with {@code NOT_FOUND} if no settings row
     *     exists
     */
    MessagePermission messagePermissionOf(UUID userId);
}
