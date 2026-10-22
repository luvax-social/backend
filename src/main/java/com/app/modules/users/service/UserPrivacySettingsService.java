package com.app.modules.users.service;

import java.util.UUID;

import com.app.modules.users.enums.MessagePermission;
import com.app.modules.users.enums.TagPermission;

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

    /**
     * Returns who may tag the account and whether its tags wait for approval.
     *
     * @param userId the account whose preferences are read
     * @return the stored preferences, or the column defaults when the account has no settings row
     */
    TagSettings tagSettingsOf(UUID userId);

    /**
     * The two tag preferences the interaction policy applies to a tag of an account.
     *
     * @param permission who may tag the account
     * @param manualApproval whether a tag of the account must be approved before it is shown
     */
    record TagSettings(TagPermission permission, boolean manualApproval) {}
}
