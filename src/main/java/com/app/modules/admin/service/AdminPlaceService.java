package com.app.modules.admin.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminUpdatePlaceRequest;
import com.app.modules.admin.dto.response.AdminActionResponse;
import com.app.modules.post.place.dto.response.PlaceAdminResponse;
import com.app.modules.post.place.enums.PlaceStatus;

/**
 * Administrative places registry: reads that span every status, and the ban, unban and delete
 * transitions, each writing an audit row in the same transaction as the status change.
 *
 * <p>Every method asserts that the actor is an administrator; a moderator may not manage the
 * registry.
 */
public interface AdminPlaceService {

    /**
     * Lists places newest first, optionally narrowed to one status.
     *
     * @param actorId the administrator reading
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return one cursor page of places
     */
    CursorPageResponse<PlaceAdminResponse> listPlaces(
            UUID actorId, PlaceStatus status, String cursor, int limit);

    /**
     * Searches places by substring of the name, optionally narrowed to one status.
     *
     * @param actorId the administrator reading
     * @param query search text
     * @param status status to match, or null for every status
     * @param cursor opaque cursor from the prior page
     * @param limit requested page size
     * @return one cursor page of matching places
     */
    CursorPageResponse<PlaceAdminResponse> searchPlaces(
            UUID actorId, String query, PlaceStatus status, String cursor, int limit);

    /**
     * Moves a place to the requested status and records the decision.
     *
     * <p>The audit action is derived from the transition: banned is {@code ban_place}, deleted is
     * {@code delete_place}, and active is {@code unban_place}, which also brings a deleted place
     * back. When the request names a report about this place, a ban or delete resolves it in the
     * same transaction.
     *
     * @param actorId the administrator deciding
     * @param placeId the place to move
     * @param request the target status, the justification and an optional linked report
     * @return the audit row the decision wrote
     */
    AdminActionResponse updatePlace(UUID actorId, UUID placeId, AdminUpdatePlaceRequest request);
}
