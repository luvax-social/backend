package com.app.modules.admin.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminCreateHashtagRequest;
import com.app.modules.admin.dto.request.AdminDeleteHashtagRequest;
import com.app.modules.admin.dto.request.AdminUpdateHashtagRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.hashtag.dto.response.HashtagAdminResponse;
import com.app.modules.hashtag.enums.HashtagStatus;

/**
 * Administrative management of the hashtag registry.
 *
 * <p>Owns the audit row and the transition guard; the hashtag module owns the side effects, the
 * same division {@code AdminService.removePost} follows with the post module. Nothing here writes
 * to {@code hashtags} directly, so the administrative path and the lifecycle rules cannot drift
 * apart.
 */
public interface AdminHashtagService {

    /**
     * Lists hashtags newest first, spanning every status unless one is named.
     *
     * @param actorId the acting administrator, checked against the source of truth
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return matching hashtags with their lifecycle state
     * @throws AppException {@code FORBIDDEN} when the actor is not an administrator
     */
    CursorPageResponse<HashtagAdminResponse> listHashtags(
            UUID actorId, HashtagStatus status, String cursor, int limit);

    /**
     * Searches hashtags by case-insensitive substring, spanning every status unless one is named.
     *
     * @param actorId the acting administrator, checked against the source of truth
     * @param query search text
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return matching hashtags with their lifecycle state
     * @throws AppException {@code FORBIDDEN} when the actor is not an administrator
     */
    CursorPageResponse<HashtagAdminResponse> searchHashtags(
            UUID actorId, String query, HashtagStatus status, String cursor, int limit);

    /**
     * Creates a hashtag directly in the requested state and records one {@code create_hashtag} row.
     *
     * @param actorId the acting administrator
     * @param request the name, the state to create it in, and the justification
     * @return the audit row written for the creation
     * @throws AppException with {@code HASHTAG_ALREADY_EXISTS} when the name is taken
     */
    AdminActionResponse createHashtag(UUID actorId, AdminCreateHashtagRequest request);

    /**
     * Moves a hashtag to a new lifecycle state and records the matching audit row.
     *
     * <p>The audit action reflects the transition rather than merely the target: reaching {@code
     * banned} is a ban, returning to {@code active} from {@code banned} is an unban, and returning
     * from {@code deleted} is an edit, because no audit value names a restore.
     *
     * @param actorId the acting administrator
     * @param hashtagId the hashtag to move
     * @param request the target state and the justification
     * @return the audit row written for the transition
     * @throws AppException with {@code HASHTAG_NOT_FOUND}, or {@code ADMIN_INVALID_TRANSITION} when
     *     the hashtag already holds the requested state
     */
    AdminActionResponse updateHashtag(
            UUID actorId, UUID hashtagId, AdminUpdateHashtagRequest request);

    /**
     * Moves a hashtag to {@code deleted} and records one {@code delete_hashtag} row.
     *
     * <p>Never a row removal. The {@code post_hashtags} associations and the trigger-maintained
     * {@code post_count} survive untouched, so a deletion is reversible and no post's history is
     * rewritten.
     *
     * @param actorId the acting administrator
     * @param hashtagId the hashtag to delete
     * @param request the justification
     * @return the audit row written for the deletion
     * @throws AppException with {@code HASHTAG_NOT_FOUND}, or {@code ADMIN_INVALID_TRANSITION} when
     *     the hashtag is already deleted
     */
    AdminActionResponse deleteHashtag(
            UUID actorId, UUID hashtagId, AdminDeleteHashtagRequest request);

    /**
     * Pins a hashtag platform-wide and records the moderation action.
     *
     * <p>Writes the {@code admin_actions} row and mutates the hashtag in the same transaction, as
     * every other action on this surface does.
     *
     * @param actorId administrator taking the action
     * @param hashtagId hashtag to pin
     * @param note optional free-text note stored on the audit row
     * @return the recorded moderation action
     */
    AdminActionResponse pinHashtag(UUID actorId, UUID hashtagId, String note);

    /**
     * Removes a platform-wide pin and records the moderation action.
     *
     * @param actorId administrator taking the action
     * @param hashtagId hashtag to unpin
     * @param note optional free-text note stored on the audit row
     * @return the recorded moderation action
     */
    AdminActionResponse unpinHashtag(UUID actorId, UUID hashtagId, String note);
}
