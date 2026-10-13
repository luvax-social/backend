package com.app.modules.social.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.social.dto.response.MuteResponse;

/**
 * Per-scope mutes: an account hiding another account's posts, stories or both from its own
 * surfaces.
 *
 * <p>A mute is private to the muting account. The muted account is never told, no notification is
 * created, and the mute events are never bound to a notification queue. Every change enqueues its
 * event in the same transaction as the write. Feeds and stories do not yet read mutes.
 */
public interface MuteService {

    /**
     * Sets what the account mutes of a target, creating or replacing the mute.
     *
     * <p>Turning both scopes off removes the mute, because a row that hides nothing is not stored.
     * An event is enqueued only when the stored state actually changed: {@code user.muted.v1} when
     * a mute was created or its scopes changed, {@code user.unmuted.v1} when one was removed.
     *
     * @param userId the muting account
     * @param targetUserId the account being muted
     * @param mutePosts whether the target's posts are hidden
     * @param muteStories whether the target's stories are hidden
     * @return the resulting mute with the target's public summary; both flags false after an unmute
     * @throws com.app.common.exception.AppException with {@code BAD_REQUEST} when the target is the
     *     caller, or {@code NOT_FOUND} when the target is missing or in a block relationship with
     *     the caller in either direction, which is deliberately indistinguishable from a missing
     *     one
     */
    MuteResponse setMute(UUID userId, UUID targetUserId, boolean mutePosts, boolean muteStories);

    /**
     * Removes the account's mute of a target.
     *
     * <p>Idempotent and independent of the target's current state. {@code user.unmuted.v1} is
     * enqueued only when a mute existed.
     *
     * @param userId the muting account
     * @param targetUserId the account that was muted
     */
    void unmute(UUID userId, UUID targetUserId);

    /**
     * Lists the account's mutes, most recently set first.
     *
     * <p>A muted account since soft-deleted stays in the list as a placeholder summary, so the page
     * length matches the row count.
     *
     * @param userId the muting account
     * @param cursor opaque cursor from the previous page, or null for the first page
     * @param limit page size, clamped to 1 to 100
     * @return a page of mutes with each target's summary and both scope flags
     * @throws com.app.common.exception.AppException with {@code INVALID_CURSOR} when the cursor is
     *     malformed or was issued by another listing
     */
    CursorPageResponse<MuteResponse> listMutes(UUID userId, String cursor, int limit);
}
