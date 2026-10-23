package com.app.modules.post.service;

import java.util.UUID;

import com.app.common.response.CursorPageResponse;
import com.app.modules.post.dto.response.PostResponse;

/**
 * Published posts tagged at a given place: the location page.
 *
 * <p>Mirrors {@link PostByHashtagService}. Elasticsearch is the primary path and PostgreSQL,
 * through {@code posts.place_id}, the fallback when the {@code elasticsearchSearch} circuit breaker
 * is open, so the page is complete either way.
 */
public interface PostByPlaceService {

    /**
     * Lists published posts tagged at the place, newest publication first.
     *
     * <p>Refuses the whole call when the place was banned or deleted, rather than answering an
     * empty page, so a client can tell an unavailable place from one with no posts yet. Both tiers
     * page on the same offset cursor, hits are re-checked against PostgreSQL for published status,
     * and every post is filtered through the shared visibility chain, so a page may hold fewer
     * items than requested. A co-author that is blocked with the viewer hides the post like the
     * owner would.
     *
     * @param viewerId authenticated viewer whose blocks and private-account access govern the page
     * @param placeId the place
     * @param cursor opaque base64 offset cursor; null or blank for the first page
     * @param size maximum results per page
     * @return cursor-paginated visible posts, newest first
     * @throws com.app.common.exception.AppException {@code PLACE_NOT_FOUND} when no row carries the
     *     id, {@code PLACE_UNAVAILABLE} when the place is banned or deleted, {@code
     *     PAGINATION_DEPTH_EXCEEDED} when the window is deeper than the endpoint serves, or {@code
     *     INVALID_CURSOR} when the cursor is malformed
     */
    CursorPageResponse<PostResponse> findPostsByPlace(
            UUID viewerId, UUID placeId, String cursor, int size);
}
