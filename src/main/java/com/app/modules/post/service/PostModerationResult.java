package com.app.modules.post.service;

import java.util.List;
import java.util.UUID;

import com.app.modules.post.enums.PostStatus;

/**
 * Outcome of a moderation removal or restore.
 *
 * @param ownerId author of the moderated post, for the audit row the caller writes
 * @param status status the post holds after the operation
 * @param remainingBannedHashtags normalized names of hashtags the caption still carries but that
 *     are not re-associated because they are banned; always empty on a removal, and on a restore
 *     whose result is not published
 * @param strippedPlaceName name of the place a restore dropped because an administrator banned or
 *     deleted it while the post was removed, or null when the post kept its place or had none;
 *     always null on a removal
 */
public record PostModerationResult(
        UUID ownerId,
        PostStatus status,
        List<String> remainingBannedHashtags,
        String strippedPlaceName) {

    public PostModerationResult(
            UUID ownerId, PostStatus status, List<String> remainingBannedHashtags) {
        this(ownerId, status, remainingBannedHashtags, null);
    }
}
