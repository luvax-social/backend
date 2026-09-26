package com.app.modules.hashtag.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.hashtag.dto.response.HashtagAdminResponse;
import com.app.modules.hashtag.enums.HashtagStatus;

/**
 * Administrative reads and lifecycle transitions over the canonical hashtag registry.
 *
 * <p>Separate from {@link HashtagService}, which is the association surface every post write path
 * uses. The split keeps the status-spanning reads and the status mutations out of the interface the
 * post module depends on.
 *
 * <p>Authorization and the {@code admin_actions} audit row are the admin module's concern, not this
 * one. This interface performs the side effects of a decision that has already been taken, the same
 * division {@code PostService.applyModerationRemoval} follows.
 */
public interface HashtagLifecycleService {

    /**
     * Lists hashtags newest first, spanning every status unless one is named.
     *
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return matching hashtags in {@code (created_at DESC, id DESC)} order
     */
    CursorPageResponse<HashtagAdminResponse> list(HashtagStatus status, String cursor, int limit);

    /**
     * Searches hashtags by case-insensitive substring, spanning every status unless one is named.
     *
     * @param query search text
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return matching hashtags in {@code (created_at DESC, id DESC)} order
     * @throws AppException with {@code BAD_REQUEST} when the query is shorter than the minimum
     */
    CursorPageResponse<HashtagAdminResponse> search(
            String query, HashtagStatus status, String cursor, int limit);

    /**
     * Creates a hashtag directly in the requested state, ahead of any post using it.
     *
     * <p>Serves pre-seeding a term before an event and pre-banning one before it can be used.
     * Creating a hashtag already deleted is refused, because it would name a state nothing can
     * reach it from.
     *
     * @param actorId the administrator creating the hashtag
     * @param rawName the hashtag name, normalized by this method
     * @param status the state to create it in; active or banned
     * @param note the administrator's justification, recorded on the row
     * @return the created hashtag and the transition it represents
     * @throws AppException with {@code HASHTAG_ALREADY_EXISTS} when the name is taken, or {@code
     *     BAD_REQUEST} when the name normalizes to nothing or the requested state is deleted
     */
    HashtagLifecycleResult create(UUID actorId, String rawName, HashtagStatus status, String note);

    /**
     * Moves a hashtag to a new lifecycle state, recording who decided and why.
     *
     * <p>Taking a hashtag out of circulation also removes its {@code hashtag_trending} rows in this
     * same transaction, and enqueues a search-index sync event. A hashtag is usually banned in
     * reaction to something happening right now, which is exactly when it is at the top of the
     * trending list.
     *
     * @param actorId the administrator making the decision
     * @param hashtagId the hashtag to move
     * @param target the state to move it to
     * @param note the administrator's justification, recorded on the row
     * @return the hashtag, the state it left, and the state it now holds
     * @throws AppException with {@code HASHTAG_NOT_FOUND} when no such hashtag exists, or {@code
     *     ADMIN_INVALID_TRANSITION} when the hashtag already holds the requested state
     */
    HashtagLifecycleResult changeStatus(
            UUID actorId, UUID hashtagId, HashtagStatus target, String note);

    /**
     * Pins a hashtag platform-wide so it leads the trending list.
     *
     * <p>Refused when the hashtag is banned or deleted: a pin promotes a term, and a term an
     * administrator has taken out of circulation must not be promoted. Refused again when the
     * hashtag is already pinned, because there is no state change to record.
     *
     * @param actorId administrator taking the action, recorded as the pinning administrator
     * @param hashtagId hashtag to pin
     * @return the resulting lifecycle state, for the caller's audit row
     * @throws com.app.common.exception.AppException {@code HASHTAG_NOT_FOUND} when no row carries
     *     the id, {@code HASHTAG_UNAVAILABLE} when it is banned or deleted, or {@code
     *     ADMIN_INVALID_TRANSITION} when it is already pinned
     */
    HashtagLifecycleResult pin(UUID actorId, UUID hashtagId);

    /**
     * Removes a platform-wide pin.
     *
     * @param actorId administrator taking the action
     * @param hashtagId hashtag to unpin
     * @return the resulting lifecycle state, for the caller's audit row
     * @throws com.app.common.exception.AppException {@code HASHTAG_NOT_FOUND} when no row carries
     *     the id, or {@code ADMIN_INVALID_TRANSITION} when it is not pinned
     */
    HashtagLifecycleResult unpin(UUID actorId, UUID hashtagId);
}
