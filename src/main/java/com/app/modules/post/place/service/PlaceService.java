package com.app.modules.post.place.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.app.common.exception.AppException;
import com.app.modules.post.place.dto.response.PlaceResponse;
import com.app.modules.post.place.dto.response.PlaceSummaryResponse;
import com.app.modules.post.place.entity.Place;

/**
 * Creation and lookup of places, and the check every post write path runs before it accepts one.
 *
 * <p>Administrative reads and status changes belong to {@link PlaceLifecycleService}, which keeps
 * the interface the post module depends on small, the same split the hashtag module has.
 */
public interface PlaceService {

    /**
     * Returns the place with this name, creating it when no place has the normalised name yet.
     *
     * <p>Case, accents and spacing never create a duplicate. A name that matches a banned or
     * deleted place is refused rather than recreated.
     *
     * @param actorId the account creating or using the name
     * @param rawName the name as typed
     * @return the place
     * @throws AppException with {@code PLACE_NAME_INVALID} when the name is too short or long,
     *     contains a control character or holds no letter or digit, or {@code
     *     POST_PLACE_UNAVAILABLE} when the name belongs to a banned or deleted place
     */
    PlaceResponse findOrCreate(UUID actorId, String rawName);

    /**
     * Returns a place that is usable on every surface.
     *
     * @param placeId the place
     * @return the place
     * @throws AppException with {@code PLACE_NOT_FOUND} when no place has the id, or {@code
     *     PLACE_UNAVAILABLE} when it is banned or deleted
     */
    PlaceResponse getUsable(UUID placeId);

    /**
     * Resolves the place a post write names, by id or by typed name, and refuses an unavailable
     * one.
     *
     * @param actorId the account writing the post
     * @param placeId the id of an existing place, or null
     * @param placeName a typed name, used when no id is given, or null
     * @return the usable place, or null when the write names no place
     * @throws AppException with {@code POST_PLACE_UNAVAILABLE} when the place is banned or deleted,
     *     {@code PLACE_NOT_FOUND} when the id is unknown, or {@code PLACE_NAME_INVALID}
     */
    Place resolveForPostWrite(UUID actorId, UUID placeId, String placeName);

    /**
     * Asserts that a place a post already carries may be published, without creating anything.
     *
     * @param placeId the place, or null when the post has none
     * @throws AppException with {@code POST_PLACE_UNAVAILABLE} when the place is banned or deleted
     */
    void assertPublishable(UUID placeId);

    /**
     * Loads the place line for each id, leaving out places that are banned or deleted so no surface
     * shows them.
     *
     * @param placeIds place identifiers, null entries ignored
     * @return summaries keyed by id; absent for an unavailable place
     */
    Map<UUID, PlaceSummaryResponse> loadActiveSummaries(Collection<UUID> placeIds);
}
