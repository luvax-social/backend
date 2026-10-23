package com.app.modules.post.place.service;

import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.common.response.CursorPageResponse;
import com.app.modules.post.place.dto.response.PlaceAdminResponse;
import com.app.modules.post.place.enums.PlaceStatus;

/**
 * Administrative reads and lifecycle transitions over the places registry.
 *
 * <p>Authorization and the {@code admin_actions} audit row are the admin module's concern. This
 * interface performs the side effects of a decision that has already been taken, the same division
 * the hashtag lifecycle follows.
 */
public interface PlaceLifecycleService {

    /**
     * Lists places newest first, spanning every status unless one is named.
     *
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return matching places in {@code (created_at DESC, id DESC)} order
     */
    CursorPageResponse<PlaceAdminResponse> list(PlaceStatus status, String cursor, int limit);

    /**
     * Searches places by case-insensitive substring, spanning every status unless one is named.
     *
     * @param query search text
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return matching places in {@code (created_at DESC, id DESC)} order
     * @throws AppException with {@code BAD_REQUEST} when the query is blank
     */
    CursorPageResponse<PlaceAdminResponse> search(
            String query, PlaceStatus status, String cursor, int limit);

    /**
     * Moves a place to a new lifecycle state, recording who decided and why.
     *
     * <p>Enqueues a search-index sync event in the same transaction, which the consumer turns into
     * an index delete for a banned or deleted place and an upsert when it comes back.
     *
     * @param actorId the administrator making the decision
     * @param placeId the place to move
     * @param target the state to move it to
     * @param note the administrator's justification, recorded on the row
     * @return the place, the state it left and the state it now holds
     * @throws AppException with {@code PLACE_NOT_FOUND} when no such place exists, or {@code
     *     ADMIN_INVALID_TRANSITION} when the place already holds the requested state
     */
    PlaceLifecycleResult changeStatus(UUID actorId, UUID placeId, PlaceStatus target, String note);
}
